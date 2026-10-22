# Kit library verification

The package API is `field-kit.json` (`field.kit.package.v1`). Existing role kits (`kit.json`) stay
independent. `KitPackagesTest` exercises registration without execution, immutable revisions,
classpath conflict refusal, instance records, three-way migration, Project scope identity and
preferences, lifecycle failure/cleanup, and existing-launcher compatibility. Run it with the existing
`KitsTest`, `FileManagerFallbackTest`, `BoxTopologyConcurrencyTest` and `PersonalLayerStagingTest`.

## Live rehearsal, 2026-10-22

An isolated workspace and registry (`AFIELD_KITS_DIR`) were launched from this worktree, with a
separate Electron profile. The test package contained:

- Kotlin extension source with a `KitLifecycle` implementation;
- a pinned Maven dependency, `org.jsoup:jsoup:1.16.1`, absent from the core launch classpath;
- a small precompiled support JAR;
- a JNI method returning 42 from a package-local native library;
- an extension classpath resource and a shared package file;
- a presentation starting point whose launch box calls all these parts, reads an inherited Project
  value set by activation and saves a Project preference.

The actual Welcome controls registered it without executing its lifecycle. Creating a document was
refused until restart. **Restart Field** compiled the kit before stopping the old host, then started
a new JVM on the same ports and workspace while retaining the same Electron process. A guide-only
package revision also survived a second restart; immutable package JARs gained no staging files.
The box's own execution verdict was successful and its real output read:

```text
dependency|jar|42|resource|shared-parent|shared-file
```

The output was opened in the real Field tab. The saved Project preference was checked on disk.
Closing the document invoked its deactivate hook; restarting invoked cleanup followed by startup.

A deliberately invalid extension was then registered. Runtime preparation failed at
`compilePersonalKotlinJvm`, leaving the original JVM running, the error visible and Welcome controls
usable. Restoring the valid source returned the library to its loaded revision without shutdown.

An existing private project was separately registered using only a new outer descriptor. Hashes of
its existing launcher, custom manifest, starting document and native manifest matched before and
after registration. Its dedicated host was not launched or restarted, and its runtime was excluded
from the current host's classpath profile. That is registration/compatibility evidence; it does not
claim a migration or a fresh run of the private project.

## Disclosure check

In actual Electron, **Kits** is a collapsed row below Starting points and Templates. Enter toggles it.
The expanded body scrolls within 42vh. A pending runtime change remains visible in the summary.
Refreshing the Starting points dock preserves the Kit input element, typed path and expansion state:
the library is its sibling, outside the dock's rebuilt subtree. Create, update and restart actions
were exercised from that same disclosure. No synthetic capture is committed as documentation media.
