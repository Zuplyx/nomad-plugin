---
status: accepted
---
# ADR-0001: Drop MigrationHelper and pre-0.9.0 config support

## Context

`MigrationHelper` arrived in `42015f0` (2021-10-26, PR #82), which replaced the plugin's structured
job builder with a single free-form `jobTemplate` string. To avoid breaking existing installations,
that commit kept as migration scaffolding:

- `MigrationHelper` itself (351 lines, reflection-based field access),
- 17 `Api/*` model classes whose only caller is `MigrationHelper.buildWorkerJob`,
- three `Nomad*Template` describables whose `config.jelly` files it deleted,
- 28 `@Deprecated transient` fields on `NomadWorkerTemplate` and three on `NomadCloud`,
- nine XML fixtures and a parameterised test asserting the migration's output.

It has shipped in every release since, v0.9.0 (2021-10-27) through v0.11.0.

Two forces pull against each other:

1. **The scaffolding dominates the codebase.** It is roughly half the source and the only consumer
   of a third of the classes, so any work on the HTTP or job-building layer has to keep compiling
   code that exists solely to read configurations last written in 2021.
2. **Jenkins guidance pulls the other way.** Migrating an old `config.xml` is
   [ordinary practice](https://www.jenkins.io/doc/developer/persistence/backward-compatibility/),
   and [marking a release incompatible](https://www.jenkins.io/doc/developer/plugin-development/mark-a-plugin-incompatible/)
   is a last resort. That guidance sets no expiry, though: it says how to stay compatible, never for
   how long, leaving that judgement to the maintainer.

The blast radius is narrow, which is what makes the decision tractable. A configuration is affected
only if it was **last written by a plugin older than 0.9.0 and never re-saved since**; anyone on
v0.9.0 or later who has saved their cloud configuration once already has a `jobTemplate` persisted.

## Decision

Delete the migration scaffolding in **0.12.0** and declare the break explicitly, rather than
deleting it quietly and relying on nobody noticing.

Concretely: remove all of the above and set
`<hpi.compatibleSinceVersion>0.12.0</hpi.compatibleSinceVersion>` so the Update Center marks the
upgrade as potentially requiring reconfiguration.

## Options Considered

### Option A: Delete it, declare the break (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low — deletion, plus one POM property |
| Cost | 34 files and ~2,500 lines removed |
| Maintenance burden | Removed entirely |
| Alignment with Jenkins guidance | Partial — a deliberate, declared exception to it |

**Pros:** Removes ~half the source and lifts a standing constraint on changing the HTTP and
job-building layers; the affected population is plausibly empty; the break is announced through the
mechanism Jenkins provides for exactly this.
**Cons:** A pre-0.9.0 configuration silently loses its worker templates; irreversible for a user
who upgrades without reading the changelog.

### Option B: Keep MigrationHelper indefinitely

| Dimension | Assessment |
|-----------|------------|
| Complexity | High — every future change reasons around dead code |
| Cost | Zero now, compounding later |
| Maintenance burden | Permanent |
| Alignment with Jenkins guidance | Full |

**Pros:** No user can be broken; sits squarely inside Jenkins' backward-compatibility guidance.
**Cons:** Freezes half the codebase permanently to serve configurations last saved in 2021; the
`Api/*` classes have to be carried through any later change to the API layer, and the Vault stanza
they emit is invalid on Nomad 1.10+ anyway, so the migration path is already partly broken.

### Option C: Migrate once, then delete in a later release

| Dimension | Assessment |
|-----------|------------|
| Complexity | High — needs a release that force-saves every cloud |
| Cost | An extra release cycle plus new migration-on-boot code |
| Maintenance burden | Temporary but real |
| Alignment with Jenkins guidance | Full |

**Pros:** Nobody loses configuration, and the code still goes away eventually.
**Cons:** Requires writing *more* migration machinery to retire migration machinery, and force-
saving user configuration on upgrade is itself intrusive. Only worthwhile if affected installations
are known to exist; there is no evidence any do. Crucially, it automates something an affected user
can already do by hand: upgrade to 0.11.0, which still migrates, open and save the cloud
configuration so the generated `jobTemplate` is persisted, then upgrade onwards. The remedy exists
without shipping any new code.

## Trade-off Analysis

What bounds Option A's cost is that the failure mode is **degraded, not fatal**. Jenkins'
`RobustReflectionConverter` reports unknown XML elements to the Old Data monitor and carries on, so
a controller still starts and a pre-0.9.0 template simply comes back empty.
`NomadCloudTest.testCloudFromOlderPluginStillLoads` pins this rather than assuming it, because the
cost of being wrong would be a controller that refuses to start.

Reaching for `hpi.compatibleSinceVersion` is meant to be rare, so it is worth saying plainly that
this is a deliberate exception. The bar is "automatic data upgrade should be used whenever
possible" — and that upgrade exists and has shipped for five releases. What is being decided is
whether to carry it indefinitely for configurations that, on the evidence of the plugin's 273
reported installs and the format's age, are unlikely to still exist. The guidance sets no expiry on
old formats, so that call is the maintainer's to make and to record.

## Consequences

**Easier**
- Changes to the HTTP and job-building layers no longer have to keep migration code compiling.
- `Api/*` shrinks to the two classes actually used at runtime (`JobInfo`, `JobSummary`).
- `json-path-assert` leaves the build; it existed only for the migration test.

**Harder**
- A configuration last written before 0.9.0 loses its worker template contents on upgrade.
- Reverting after the fact means recovering the deleted code from history, not flipping a flag.

**To revisit**
- The changelog for 0.12.0 must state the break in plain language, and give the remedy: upgrade to
  0.11.0 first, re-save the cloud configuration, then upgrade onwards. `hpi.compatibleSinceVersion`
  warns that *something* changed, not what, and not what to do about it.