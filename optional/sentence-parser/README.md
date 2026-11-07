# Optional sentence-parser environment

`./gradlew prepareSentenceParser` installs the locked dependencies, downloads the English
models and verifies a CPU parse. The worker code lives in `python/fieldsentences`.
See [its guide](../../python/fieldsentences/README.md) for serving and one-shot commands.
Retained sentence diagrams and the four reading examples need no parser environment.
