# Contributing

Comments, commit messages, and documentation are written in English.

## Development

- JDK 21 and a Domino HTTP install with the PVC servlet API (see `build.ps1`), or set `ALDHANI_SERVLET_JAR` to a `javax.servlet-api` jar for CI-style builds.
- Kyber on DRAPI for live smoke tests (`smoke.ps1`).

```powershell
.\build.ps1
.\smoke.ps1
```

## Pull requests

`main` requires a green **build** check. Open a branch and a PR; do not push straight to `main` unless you are bypassing protection for an emergency.

1. Keep the change focused.
2. Run `.\build.ps1` locally when you can.
3. Update the version in `build.ps1`, `feature.xml`, and `MANIFEST.MF` together when you ship a release.
4. After merge to `main`, push tag `vX.Y.Z`. The **Release** workflow builds with `javax.servlet-api`, creates the GitHub release, and attaches `infoware-aldhani-X.Y.Z.jar`. The tag version must match `build.ps1`.

## Related

- Design API: [Kyber](https://github.com/infoware-se/kyber)
- Security reports: [SECURITY.md](SECURITY.md)
