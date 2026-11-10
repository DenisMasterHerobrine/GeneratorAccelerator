# Benchmarks

Run the regression suite from the repository root:

```powershell
.\gradlew.bat regrssionBenchmark
```

It also runs during Fabric `check` and the normal `build`. The current suite
takes about 4–5 minutes and has a 15-minute timeout. Compilation finishes before
the measurements start; JUnit tests run afterwards.

## Reading the results

Results are saved under `fabric/benchmark-results/`. Start with
`regrssionBenchmark.md`: it shows the latest run, the previous tested commit,
and how each measurement compares with its saved best. Results are grouped by
category and scenario. Extra GC statistics are tucked into collapsible sections.

The other files are:

| Path                        | Contents                                                     |
|-----------------------------|--------------------------------------------------------------|
| `best/regression/best.md`   | Best results, with the commit and run that produced each one |
| `best/regression/best.json` | The same records in machine-readable form                    |
| `reports/regression/`       | A report for each run, plus `latest.md`                      |
| `history/regression/`       | Measurement history and commit metadata                      |
| `raw/regression/`           | Original JMH output                                          |

These files survive `clean` and are ignored by Git. Keep them between CI runs,
or import a baseline with `jmh.baseline`, so the next run has something to compare.

## How records work

Each benchmark, mode, thread count and parameter combination has its own records.
Time and bytes allocated per operation (B/op) are tracked separately. A faster
run can set a time record while leaving the allocation record untouched.

Any strictly better score replaces its record: lower for time and B/op, higher
for throughput. Equal or worse scores leave the record, its commit and its date
alone. If nothing improves, neither `best.json` nor `best.md` is rewritten.
Every run is still added to the history.

The regression check uses a separate rule: a result must be more than 5% worse
than its record, with non-overlapping confidence intervals, to fail the build.
Allocation growth from a zero baseline also fails. `jmh.failOnRegression=false`
does not disable this check in the mandatory suite.

The commit SHA and working-tree status are captured before JMH starts. Records
from uncommitted changes are marked `dirty`; missing commit information is shown
as `unknown`. Time and allocation records can belong to different commits.

On the first run with the default `regression` tag, existing
`history/flat-climate/` results are imported. The importer picks the best score
from the whole history and keeps its original commit. It also recognizes the
old benchmark package names, so moving the classes does not discard old results.

Use the same machine, JDK and JVM options for comparisons. Give another machine
its own `jmh.tag` to keep its records separate. Measurements must use matching units.

## FlatClimateIndex

The suite runs 16 operations in each of two scenarios, for 32 combinations:

| Operation                              | Measures                                                    |
|----------------------------------------|-------------------------------------------------------------|
| `search`                               | Tree traversal using the previous answer                    |
| `columns`, `outsideFill`               | Column search inside and outside a biome fill               |
| `walkColumns`                          | Column traversal without the grouped fast path              |
| `fittest`, `fittestColumns`            | The two nearest leaves with different biomes                |
| `walkFittestColumns`, `reusedFittest`  | The explicit column walk and the thread-local result object |
| `alone`, `aloneByValue`, `secondAlone` | Tie checks for leaves, values and the second biome          |
| `distance`, `build`                    | Distance calculation and flat-tree construction             |
| `coldCache`, `adaptiveCache`           | Cache invalidation, rebuilding and adaptive backoff         |
| `lifecycle`                            | Nested fills, counters and tree diagnostics                 |

`realistic` uses vanilla Overworld and Nether trees, two seeds, and four chunks
per seed and dimension over the full height range. Its 16,384 climate samples
follow vanilla's section/x/y/z fill order. The adaptive-cache test uses another
8,192 samples from scattered world positions.

`absurd` uses six synthetic trees with up to 4,096 leaves each and 6,144 queries.
It includes distance ties, 64 and 65 depth groups, single-leaf and small trees,
cache eviction, and bounds and queries large enough to overflow squared `long`
distances. Two-nearest searches skip single-leaf roots, as their API requires a subtree.

Each combination gets one JVM fork, one thread, five warmup iterations and five
measurement iterations of 200 ms each. Operations run in batches of 64, with
JMH reporting the cost per operation. The JVM heap is 1–2 GiB.

GC profiling is always enabled. B/op counts Java heap allocations; it does not
measure retained heap or native/GPU memory. Sampling and correctness checks
happen during setup, so the FlatClimateIndex results exclude noise generation.

All 32 combinations must produce valid results before comparison. Adding another
category does not remove this requirement. `jmh.quick`, `jmh.include`, `jmh.args`
and `jmh.gc` do not shorten or change the mandatory run.

## Adding benchmarks

JMH classes live under
`common/src/jmh/java/dev/denismasterherobrine/generatoraccelerator2/bench/`.
Their packages determine the report categories:

| Package after `bench.`            | Report category          |
|-----------------------------------|--------------------------|
| `biome.flatclimateindex`          | Biome → FlatClimateIndex |
| `optional.biome`                  | Biome                    |
| `optional.biome.flatclimateindex` | Biome → FlatClimateIndex |

The regression task discovers benchmarks in `bench.*` automatically, except
those in `bench.optional.*`. Add a JMH class to the appropriate package to include
it. Additional package levels become additional category levels in the report.

Workload helpers stay in the production `biome` package because they call
package-private methods. The JMH class, rather than its helper, sets the category.

## Other commands

```powershell
# Keep a separate set of records for this machine.
.\gradlew.bat regrssionBenchmark '-Pjmh.tag=workstation'

# Allow a 10% regression before failing. Record updates still use the better score.
.\gradlew.bat regrssionBenchmark '-Pjmh.threshold=10'

# Import a baseline. It only replaces records that it improves.
.\gradlew.bat regrssionBenchmark '-Pjmh.baseline=H:/results/baseline.json'

# Run all benchmarks, including the optional ones.
.\gradlew.bat :fabric:jmhRegression

# Check one optional benchmark quickly.
.\gradlew.bat :fabric:jmhRegression '-Pjmh.quick' '-Pjmh.include=BiomeBenchmark' '-Pjmh.args=-p preset=NETHER -p distribution=columns -p operation=flat'

# Check record handling and benchmark inputs without taking new measurements.
.\gradlew.bat :fabric:jmhRegressionTest :fabric:test --tests '*FlatClimateBenchmarkFixtureTest'
```

`jmh.resultsDir` changes the output directory; relative paths start at `fabric/`.
Each tag gets its own history, records and reports. The top-level
`regrssionBenchmark.md` is written for the default `regression` tag.
