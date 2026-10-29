-- Thread CPU runtime and slice statistics
SELECT
    t.name AS thread_name,
    p.name AS process_name,
    t.tid,
    COUNT(s.id) AS slices_count,
    ROUND(SUM(s.dur) / 1e6, 3) AS total_cpu_time_ms,
    ROUND(AVG(s.dur) / 1e6, 3) AS avg_slice_time_ms,
    ROUND(MAX(s.dur) / 1e6, 3) AS max_slice_time_ms
FROM sched s
JOIN thread t USING (utid)
LEFT JOIN process p USING (upid)
WHERE (p.name LIKE '%llama%' OR t.name LIKE '%llama%')
GROUP BY t.utid
ORDER BY total_cpu_time_ms DESC;
