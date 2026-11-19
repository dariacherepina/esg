# Hadolint DL3059 in Dockerfile.cache-wrong: intentionally not fixed

**Finding:** `DL3059 info: Multiple consecutive RUN instructions. Consider consolidation.`
**Files:** `company-service`, `esg-score-service`, `portfolio-service`, `report-service` / `Dockerfile.cache-wrong` (4 findings, see `report-before.txt`)
**Decision:** suppressed with `# hadolint ignore=DL3059` on the line above the second `RUN`; not consolidated.

## What the finding means

`cache-wrong` has two `RUN` instructions directly after each other:

```dockerfile
COPY report-service ./report-service
RUN mvn -B -q -f report-service/pom.xml dependency:go-offline
RUN mvn -B -q -f report-service/pom.xml package -DskipTests
```

Hadolint suggests merging them into a single `RUN` joined with `&&`:

```dockerfile
COPY report-service ./report-service
RUN mvn -B -q -f report-service/pom.xml dependency:go-offline \
 && mvn -B -q -f report-service/pom.xml package -DskipTests
```

Every `RUN` creates its own image layer. Consecutive `RUN`s usually have no reason to be separate, so merging them reduces the number of layers and the overhead per layer.

## Why it is not fixed

Experiment 2 compares `Dockerfile.cache-right` with `Dockerfile.cache-wrong`. `cache-right` must keep two separate `RUN`s, because its point is that the dependency layer stays cached on its own:

```dockerfile
COPY report-service/pom.xml ./report-service/
RUN mvn ... dependency:go-offline        # layer 1: dependencies
COPY report-service/src ./report-service/src
RUN mvn ... package -DskipTests          # layer 2: build
```

The independent variable of Experiment 2 is **only the instruction order**: whether `src/` is copied before or after the dependency download. Both files therefore use the same Maven commands in the same number of `RUN` instructions.

Merging the `RUN`s in `cache-wrong` would introduce a second difference (number of layers in addition to the order). A difference in rebuild time could then no longer be attributed to the instruction order alone.

For this experiment, merging would not change the outcome much: in `cache-wrong` a change in `src/` invalidates both layers anyway. Keeping the structure identical keeps the comparison clean and the argument simple.

## Summary for the thesis

DL3059 was reported in the four `cache-wrong` Dockerfiles and deliberately left in place (suppressed via an inline ignore). Consolidating the two `RUN` instructions would make `cache-wrong` differ from `cache-right` in the number of layers as well as in instruction order, confounding the single independent variable of Experiment 2.
