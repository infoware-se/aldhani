# Aldhani

Aldhani is an OSGi servlet on the Domino HTTP task. It accepts MCP and reads design through Kyber on DRAPI. Design changes stay in Kyber. This version is read-only.

The sandbox database is `aldhanitest.nsf`. It needs the catalog category `KyberEnabled`.

## Build

Requires JDK 21 and the Domino servlet API (`com.ibm.pvc.servlet`). `build.ps1` runs the self-test with Domino's `java` and falls back to `javac` on `PATH` when the Domino JVM has no compiler.

```powershell
.\build.ps1
```

That produces `target\infoware-aldhani-0.1.1.jar`. The jar is the OSGi plugin and also contains `feature.xml`. The build runs an in-process self-test and does not call the server.

## Smoke

`smoke.ps1` calls a running servlet. It checks that anonymous `POST /mcp` is rejected, then reads `aldhanitest.nsf`: `SmokeForm`, `SmokeView`, the `SmokeAgent*` agents, and `SmokeXPage.xsp`. It also exports the design and checks that `kybertest.nsf` stays outside the allowlist.

```powershell
.\smoke.ps1
```

The token is read from `<Domino data>\aldhani.json`. Pass `-Url` and `-Config` for another server.

## Install

`install.ps1` copies the jar into the applications plugins directory, unpacks `feature.xml`, and adds the feature to the applications site in `platform.xml`. Equinox on that site uses the `USER-EXCLUDE` policy, so the feature has to be listed there, and it reads the feature as a directory.

Stop HTTP first. `tell http restart` does not reload this configuration, and HTTP rewrites `platform.xml` from memory when it shuts down.

```text
tell http quit
.\install.ps1 -DataDir <Domino data>
load http
```

Do not restart the Windows service. The script refuses to edit the server while `nhttp` is running.

Copy `aldhani.json.example` to `<Domino data>\aldhani.json` and fill in the password and token. The servlet reloads that file when it changes. A new jar still needs HTTP to be started again.

`ALDHANI_CONFIG` can point at a different configuration file.

## Calling it

The endpoint is `POST /mcp`. `GET` returns 405.

Domino HTTP with session authentication consumes `Authorization: Basic` and `Authorization: Bearer` and answers with the login page before the servlet sees the request. Send the token as `Authorization: Aldhani <token>`, the same value as `bearerToken` in `aldhani.json`. `X-Aldhani-Token` works as well.

Cursor, in `.cursor/mcp.json` (that file is gitignored):

```json
{
  "mcpServers": {
    "aldhani": {
      "url": "https://dantooine.oninfoware.local/mcp",
      "headers": {
        "Authorization": "Aldhani <token>"
      }
    }
  }
}
```

## Tools

| Tool | What it does |
| --- | --- |
| `list_databases` | Kyber databases that are on the allowlist. Others are omitted. Names that are missing are listed under `missing`. |
| `list_design` | Forms, views, agents, or XPages. `type` is `form`, `view`, `agent`, or `xpage`. |
| `get_design` | One design element as Kyber JSON, including its DXL. |
| `export_design` | The whole design as DXL. |

`nsfPath` is required only when the allowlist has more than one database. Paths containing `..`, a leading `/`, or `:` are rejected before Kyber is called.

## Left out of git

`aldhani.json`, `.env`, and `.cursor/mcp.json` hold passwords and tokens. They are listed in `.gitignore`.
