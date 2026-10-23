# Aldhani

Aldhani is an OSGi servlet on the Domino HTTP task. It accepts MCP and reads design through Kyber on DRAPI. Design changes stay in Kyber. This version is read-only.

The sandbox database is `aldhanitest.nsf`. It needs the catalog category `KyberEnabled`.

## Build

Requires JDK 21 and the Domino servlet API (`com.ibm.pvc.servlet`). `build.ps1` runs the self-test with Domino's `java` and falls back to `javac` on `PATH` when the Domino JVM has no compiler.

```powershell
.\build.ps1
```

That produces

- `target\infoware-aldhani-0.1.0.jar`
- `target\se.infoware.domino.aldhani.feature_0.1.0.jar`

## Install

Copy the plugin jar and unpack the feature. The HTTP task's Equinox reads features as directories, and the `applications` site uses the `USER-EXCLUDE` policy, so the feature has to be listed in `platform.xml`.

```text
<Domino data>\domino\workspace\applications\eclipse\plugins\se.infoware.domino.aldhani_0.1.0.jar
<Domino data>\domino\workspace\applications\eclipse\features\se.infoware.domino.aldhani.feature_0.1.0\feature.xml
```

Add the feature under the applications site in `domino\workspace\.config\org.eclipse.update\platform.xml`:

```xml
<feature id="se.infoware.domino.aldhani.feature" url="features/se.infoware.domino.aldhani.feature_0.1.0/" version="0.1.0">
</feature>
```

Do that while HTTP is stopped. `tell http restart` does not reload the OSGi configuration. Stop and start only the HTTP task:

```text
tell http quit
load http
```

Do not restart the Windows service.

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
