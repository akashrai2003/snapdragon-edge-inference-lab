# Edge AI Inference on Qualcomm Snapdragon: Systems Deep Dive

This document provides a comprehensive systems-level engineering analysis of on-device AI inference on modern Qualcomm Snapdragon SoCs.

---

## 1. Mobile SoC Memory Architecture: UMA vs. Discrete GPU

When developers transition from server/desktop GPUs (NVIDIA A100/H100, RTX 4090) to mobile platforms, their fundamental assumptions about memory often break.

```
DESKTOP ARCHITECTURE (Discrete VRAM)
┌──────────────┐     PCIe 4.0/5.0 Bus (16-64 GB/s)      ┌──────────────┐
│  Host DRAM   │ ◄════════════════════════════════════► │   GPU VRAM   │
│  (DDR4/DDR5) │           cudaMemcpy / Staging         │ (GDDR6/HBM3) │
└──────────────┘                                        └──────────────┘

SNAPDRAGON MOBILE ARCHITECTURE (Unified Memory Architecture - UMA)
┌──────────────────────────────────────────────────────────────────────┐
│                 LPDDR5X DRAM (16 GB, ~100+ GB/s)                     │
└──────────────────┬─────────────────┬─────────────────┬───────────────┘
                   │ System NoC      │ System NoC      │ System NoC
        ┌──────────▼───────┐  ┌──────▼────────┐ ┌──────▼─────────┐
        │ Qualcomm Oryon   │  │ Qualcomm      │ │ Qualcomm       │
        │ 8-Core CPU       │  │ Adreno 840    │ │ Hexagon HTP    │
        │ (L1/L2 Cache)    │  │ GPU (Cache)   │ │ NPU (VMEM)     │
        └──────────────────┘  └───────────────┘ └────────────────┘
```

### The Unified Memory Reality
1. **Physical Reality:** CPU, GPU, and NPU share the **same physical LPDDR5X memory**.
2. **Logical Reality:** Each hardware accelerator operates in its own **virtual address space** with separate Memory Management Units (IOMMUs) and cache hierarchies.
3. **The Buffer Transfer Trap:** 
   - Merely having shared physical RAM does **not** make memory transfers "free" or "zero-copy".
   - When a tensor buffer allocated in userspace CPU memory (`malloc` or standard `std::vector`) is passed to OpenCL via `clCreateBuffer`, the Adreno OpenCL runtime must either:
     - Map the physical pages into the GPU's IOMMU table and execute **cache flush/invalidation** operations across the CPU cache hierarchy.
     - Allocate a device-visible driver buffer and **copy** the data over the system bus.
   - Unless zero-copy extensions (e.g., Qualcomm Ion buffers, Android AHardwareBuffer, or `CL_MEM_USE_HOST_PTR` with 64-byte alignment) are explicitly engineered, CPU-to-GPU data synchronization imposes significant latency overhead.

---

## 2. Hardware Engine Comparison on Snapdragon 8 Elite (SM8850)

| Metric / Dimension | Qualcomm Oryon CPU | Qualcomm Adreno 840 GPU | Qualcomm Hexagon HTP NPU |
|---|---|---|---|
| **Programming Model** | C/C++ POSIX threads, ARM NEON/SVE2 assembly | OpenCL 3.0 C kernels | Qualcomm Hexagon SDK, QNN, FastRPC |
| **Clock Frequency** | 3.63 GHz (Perf) - 4.61 GHz (Prime) | ~1.0 - 1.2 GHz | Variable DCVS power modes |
| **Execution Paradigm** | Low-latency out-of-order superscalar | Massively parallel SIMD / SIMT wave execution | VLIW (Very Long Instruction Word) + Tensor Accelerator (HMX/HVX) |
| **Best Workload** | Sequential control flow, small batch token decode, small matrix transforms | High-throughput batch GEMM, wide prompt processing (`pp512`) | Quantized integer matrix math (Int8/Int4), fixed-shape CNN/MLP |
| **Kernel Launch Overhead** | **Zero** (direct C++ function call) | **Medium** (OpenCL queue dispatch, barrier sync) | **High** (FastRPC user-kernel transition, graph execution) |
| **Memory Access** | Lowest latency, high L1/L2 cache locality | High burst bandwidth, texture/L2 cache | Local Vector Memory (VMEM) TCM |

---

## 3. The Mathematics of Compute vs. Memory Bound Workloads

To understand why Adreno GPU crushes the CPU on text prefill (738 t/s vs 103 t/s) but struggles on the vision projector, we analyze the **Roofline Model**:

$$\text{Arithmetic Intensity } (I) = \frac{\text{Floating Point Operations (FLOPs)}}{\text{Memory Bytes Transferred (Bytes)}}$$

```
Performance
(FLOPs/sec)
     ▲
     │                     /────────────────── Peak Compute Limit (GPU ALUs)
     │                    /
     │                   /
     │                  /
     │                 /
     │                / ◄── Memory Bandwidth Limit (Slope = Bandwidth)
     │               /
     │              /
     └─────────────┴─────────────────────────► Arithmetic Intensity (FLOPs/Byte)
            Memory Bound        Compute Bound
```

### Scenario 1: Prompt Processing (`pp512`) — Compute Bound on GPU
- When evaluating 512 prompt tokens simultaneously in the language model:
- For every weight matrix $W \in \mathbb{R}^{d \times d}$, we multiply by an input batch $X \in \mathbb{R}^{512 \times d}$.
- Operations: $2 \times 512 \times d^2$ FLOPs.
- Memory: $d^2$ bytes (loaded once from memory and reused across all 512 tokens!).
- **Arithmetic Intensity:** $I \approx 512$ FLOPs/Byte.
- **Result:** The workload is compute-bound. The massive parallel ALUs of the Adreno 840 GPU are fully saturated, delivering **7.10× higher throughput** than the CPU.

### Scenario 2: Token Generation / Auto-regressive Decode (`tg128`) — Memory Bandwidth Bound
- In auto-regressive generation, tokens are predicted **one by one** ($N=1$).
- To generate a single token, the entire 1.1 GB model weights must be streamed through the processor from RAM!
- Operations: $2 \times 1 \times d^2$ FLOPs.
- Memory: $d^2$ bytes.
- **Arithmetic Intensity:** $I \approx 2$ FLOPs/Byte.
- **Result:** The execution is bound entirely by the LPDDR5X DRAM bandwidth, not ALU compute. The GPU speedup shrinks drastically from **7.10× to only 1.43×**.

---

## 4. Understanding Liquid AI's LFM3 Architecture

LFM3 (Liquid Foundation Model) is **not a conventional transformer**. It is a **hybrid architecture** engineered for computational efficiency:

```
LFM3 HYBRID ARCHITECTURE
┌────────────────────────────────────────────────────────┐
│ Input Sequence                                         │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ 1. 1D Causal Convolutional Layer                       │
│    (Local token mixing with O(N) complexity)           │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ 2. Selective Linear State-Space / Recurrent State      │
│    (Constant memory recurrent state update O(1))       │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ 3. Selective Multi-Head Self-Attention                 │
│    (Long-range contextual dependencies)                │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│ 4. SwiGLU / Feed-Forward Network                       │
└────────────────────────────────────────────────────────┘
```

### Multimodal Vision Projector in LFM3-VL
- When processing an image (e.g. `test-512.jpg`):
  1. The image is divided into patches (e.g. $14 \times 14$ or $16 \times 16$ pixels).
  2. The vision encoder extracts visual features for each patch.
  3. The **multimodal projector (`mmproj-LFM3-VL-2B-Q8_0.gguf`)** maps visual tokens into the language model's embedding space using projection matrices, layer norms, and non-linearities.
  4. For a 512px image, the vision projector generates a sequence of visual tokens that are concatenated with text tokens.

Understanding this architecture is essential before analyzing why the vision projector runs faster on the CPU than on the GPU.
