-- CPU frequency distribution across physical cores
SELECT
    c.cpu,
    c.value / 1000 AS freq_mhz,
    ROUND(SUM(c.dur) / 1e6, 2) AS duration_ms,
    ROUND(100.0 * SUM(c.dur) / (SELECT SUM(dur) FROM counter_track t JOIN counter USING (track_id) WHERE t.cpu = c.cpu AND t.name = 'cpufreq'), 2) AS pct_time
FROM (
    SELECT
        t.cpu,
        c.value,
        LEAD(c.ts, 1, (SELECT end_ts FROM trace_bounds)) OVER (PARTITION BY t.track_id ORDER BY c.ts) - c.ts AS dur
    FROM counter c
    JOIN cpu_counter_track t ON c.track_id = t.id
    WHERE t.name = 'cpufreq'
) c
WHERE c.dur > 0
GROUP BY c.cpu, freq_mhz
ORDER BY c.cpu, freq_mhz DESC;
