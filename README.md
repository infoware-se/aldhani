# Aldhani - Infoware MCP server for HCL Domino design

An MCP server for an AI agent that creates, reads, updates, and deletes Domino design through Kyber.

Aldhani is an MCP server for HCL Domino design. It runs as an OSGi servlet on the Domino HTTP task and answers MCP at `POST /mcp`. Every design call is a Kyber call on DRAPI. Kyber remains the API that changes design. Writes are off unless `allowWrite` is true.

Version 0.1.11. It is an Infoware Solutions Svenska AB product and is published under [Apache-2.0](LICENSE). The sandbox database is `aldhanitest.nsf`. Kyber only lists a database whose catalog category is `KyberEnabled`.

Design API: [Kyber](https://github.com/infoware-se/kyber). Releases: [jar attachments](https://github.com/infoware-se/aldhani/releases). Contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Security: [SECURITY.md](SECURITY.md).

## Language

Comments, commit messages, and documentation are written in English.

## Purpose

An agent needs to work with Domino design through the products Infoware already runs. The agent speaks MCP. Domino is where that endpoint lives, because Domino is the product being worked on and the servlet installs on another Domino server the same way Kyber's jar does. Kyber on DRAPI is the design API, and it stays the only component that creates or updates a design element.

Aldhani translates MCP into Kyber calls and limits which NSF files the agent may ask about. DRAPI can stay on the server. The agent reaches `/mcp` on the HTTP task. `kyberUser` is the identity Kyber sees, for reads and for writes. Database delete, a full design import, and an ACL replacement are not tools.

## How a call moves

```mermaid
sequenceDiagram
  participant Client
  participant HTTP as Domino HTTP
  participant Aldhani
  participant Kyber as Kyber on DRAPI
  Client->>HTTP: POST /mcp
  Note over Client,HTTP: Authorization: Aldhani token
  HTTP->>Aldhani: servlet
  Aldhani->>Kyber: /api/kyber-v1 as kyberUser
  Kyber-->>Aldhani: JSON, including DXL
  Aldhani-->>Client: MCP tools/call result
```

Two checks happen on every call.

1. The servlet accepts `Authorization: Aldhani`, `X-Aldhani-Token`, or an access token Aldhani itself signed. The first two are compared with `bearerToken`.
2. The servlet logs in to Kyber with `kyberUser` and `kyberPassword`, then sends Kyber's bearer on the design request.

The allowlist is applied before Kyber is called. A path that contains `..`, starts with `/`, or contains `:` is rejected. When `allowedNsf` has one entry, `nsfPath` may be omitted and that entry is used. `list_databases` asks Kyber for databases and then keeps only allowlist names. Names on the allowlist that Kyber did not return are listed under `missing`.

## What this version leaves to Kyber

With `allowWrite` false, Aldhani only reads. With `allowWrite` true, `create_database`, `create_design`, `update_design`, `delete_design`, and `validate_design` call the matching Kyber route. `create_database` creates a missing NSF that is already on `allowedNsf`. `validate_design` writes nothing. Database delete, a full design import, and replacing the ACL stay in Kyber and are not tools.

A tool result is text. A body over 100 000 characters is returned as JSON with `truncated` and `chars`, plus `name` and `unid` when the payload has them. The DXL is left out, so the result is never cut in the middle of a string. `get_design` and `export_design` take `part`, a zero-based page of 40 000 characters of the raw body. `list_design` takes an optional `limit` and keeps the first rows. Prefer that, then `get_design` for one element.

## Improvements

Bearer login. Aldhani already issues an RS256 access token, and `POST /mcp` accepts `Authorization: Bearer` when the token is one it signed. Domino's HTTP task swallows that header and returns the login page until the internet site trusts Aldhani as an OIDC provider and has Bearer authentication enabled. The discovery document Domino reads is `GET /oauth/.well-known/openid-configuration`, and the key is `GET /oauth/jwks`. Until that trust is in place, clients keep using `Authorization: Aldhani`.

Smoke on a server with more than one allowlisted NSF. The live check expects `allowedNsf` to be exactly `aldhanitest.nsf`, so a server that also allowlists application databases fails before it exercises the tools.

A call that omits `nsfPath` when several databases are allowlisted. `onlyNsf()` returns nothing in that case and the call errors. The self-test never takes that path. A clearer error that names the allowlisted paths, or a required `nsfPath` in every tool schema when more than one NSF is listed, would stop agents guessing.

A write probe that sends real DXL. The smoke run creates `AldhaniSmokeProbe` from `<form/>` and deletes it. That shows the route accepts a body. It does not show that a design note survives a round trip. Prefer a get-after-put assertion and a `validate_design` round trip on a known good form.

Writes per database. `allowWrite` is one switch for every allowlisted NSF, so a smoke run with writes on creates and deletes `AldhaniSmokeProbe` in `aldhanitest.nsf`. A switch on the sandbox alone would leave the application databases unchanged. `repoNsf` already limits which trees are written to disk. Per-NSF `allowWrite` (or write grants) would match that model.

Missing design tools (high value). Agents still invent workflows from `inventory_design` and `get_design` for jobs that deserve a tool: `search_design` (name or substring across collections), `copy_design` (duplicate, optionally to another allowlisted NSF), `rename_design` (without delete-plus-create), `diff_design` (live DXL vs proposed or vs another note), `list_fields` and `list_columns` (form/view inventory without full DXL), `get_design_summary` (aliases, forms used, agent schedule), and `get_design` by `unid` or `noteId` (names and note ids churn after update).

Missing design tools (medium value). `patch_design` (partial replace), `list_agents_schedule`, `set_dbicon_image` (icon from image bytes or path), `import_design_element` (one DXL create-or-replace, not a full NSF import), `list_templates` / create from a named template in `kyber.nsf` (empty create only picks the first alphabetical starter), typed CRUD for bulk-only `designnote` (`_designnotes`), binary-friendly put/get for images, files, and jars without hand-built DXL, `recompile_lotusscript` after a script-library round trip, `set_kyber_enabled` on an existing NSF (today that category is set only on create/import), and surfacing Kyber's `databases.more` so agents see that allowlist filtering hid other files.

Missing design tools (lower value, still worth tracking). `repo_status` (live inventory and `unid` vs `manifest.json` / Package Explorer drift), expose Kyber export `skipped` notes, `set_db_title` without rewriting all of `applicationproperties`, structured ACL **read** as JSON (roles and entries; replace stays blocked), Domino design inherit / refresh from an NTF when Kyber supports it, dry-run create that names which `kyber.nsf` template would be copied, design-note undo or version history, `preview_starter` DXL before create, formula-only validate separate from DXL `validate_design`, list private vs shared design where Domino distinguishes them, compare inventories across two allowlisted NSFs, and chunked or resumable write for DXL larger than the MCP truncation window (create/update today assume one body).

Repo sync polish. `sync_repo` replaces a whole NSF tree; a per-element refresh after create/update already runs when `repoNsf` matches, but agents still hand-edit stale `-noteId` file names when a write races or `repoRoot` is unset. Optional `repoRoot` relative to the MCP client workspace, a tool that returns the last sync time and element counts, and refusing `sync_repo` when `repoRoot` is missing with an actionable message would help. Stale DXL left on disk after `delete_design` should be removed from the tree when sync runs.

MCP and agent UX. Prompts `review_design`, `explain_design`, and `create_element` exist but many hosts barely surface prompts; tool descriptions could embed the same workflows. `outputSchema` is only rich on `list_databases`; other tools could declare schemas. Resources are bare `aldhani://<nsf>` JSON; a resource that lists collection types, or inventory without a tool call, would cut round trips. Completions cover design `type`; completing `nsfPath` from `allowedNsf` and element `name` from the last list would reduce typos. Progress notifications on long `export_design` / `sync_repo` calls. Logging already exists; a `debug` dump of the Kyber path and status (never tokens) would speed support. Session-scoped default `nsfPath` after the agent picks a database.

Kyber dependencies. Some of the tools above need new Kyber routes first: named template pick, get-by-unid, designnote CRUD, binary resource payloads, recompile, set category on an existing NSF, design inherit from NTF, and richer validate/importer diagnostics. Aldhani only wraps what Kyber exposes. Until then, document the workaround (`inventory_design` + `get_design` loops) in the tool instructions.

Keep out of tools. Database delete, full design import or replace, and ACL replace or delete stay unavailable on purpose. Application documents, DQL, running agents, server console, compact, quota, and user administration stay outside this design MCP; another product can cover those.

## Requirements

- Domino HTTP task with the Equinox servlet registry (`javax.servlet` 2.4 or 2.5, the `com.ibm.pvc.servlet` bundle)
- Java 21
- Kyber installed on DRAPI, reachable from the HTTP task
- JDK 21 on the build machine. `build.ps1` uses `C:\Domino\jvm` when that JVM has `java`, and `javac` from `PATH` when the Domino JVM has no compiler

DRAPI's data port can stay unpublished. Aldhani calls it from the same machine. The example configuration uses `https://dantooine.oninfoware.local:8880` and trusts that certificate (`insecureTls`).

## Build

```powershell
.\build.ps1
```

The build compiles the servlet, runs `AldhaniSelfTest` in process, and writes `target\infoware-aldhani-0.1.11.jar`. That jar is the OSGi plugin. `feature.xml` is packed inside it for `install.ps1`. The self-test does not call Domino or Kyber.

`target\` is gitignored.

## Install

The HTTP task loads the feature only when it is listed in `platform.xml`. The applications site on Domino uses the `USER-EXCLUDE` policy, and Equinox reads the feature as a directory, so copying a jar into `plugins` is not enough. `install.ps1` does three things:

- copies the plugin to `plugins\se.infoware.domino.aldhani_0.1.11.jar`
- unpacks `feature.xml` to `features\se.infoware.domino.aldhani.feature_0.1.11\`
- adds the feature under the applications site in `domino\workspace\.config\org.eclipse.update\platform.xml`

Stop HTTP first. On shutdown the HTTP task rewrites `platform.xml` from memory, so an edit made while HTTP is running is lost. `tell http restart` does not reload this configuration.

```text
tell http quit
.\install.ps1 -DataDir <Domino data>
load http
```

`install.ps1` refuses to run when `nhttp` is running and `<Domino data>\notes.ini` exists. Leave the Windows service running. Restart only the HTTP task.

`-Jar` defaults to the single `target\infoware-aldhani-*.jar` from the build.

A new jar is picked up on the next HTTP start. `aldhani.json` is read again when its modification time changes, without an HTTP restart.

## Configuration

Copy `aldhani.json.example` to `<Domino data>\aldhani.json`. `ALDHANI_CONFIG` can point at another file. The example file's `_comment` key is ignored.

| Field | Meaning |
| --- | --- |
| `kyberBaseUrl` | DRAPI data URL, without a trailing slash. |
| `kyberUser` | Domino name Aldhani sends to `POST /api/v1/auth`. |
| `kyberPassword` | Password for that name. |
| `bearerToken` | Token the MCP client must send. |
| `allowedNsf` | NSF paths Kyber may be asked about. At least one. |
| `insecureTls` | Trust DRAPI's certificate and skip hostname checks. Use this for a private certificate on the same server. |
| `allowWrite` | When true, list the four write tools. Missing or false keeps an existing install read-only. |
| `repoRoot` | Optional directory. A design write refreshes that NSF here, and `sync_repo` refreshes every NSF in `repoNsf`. |
| `repoNsf` | Optional list of allowlisted NSFs to write under `repoRoot`. Missing means every allowlisted NSF. |
| `publicBaseUrl` | Optional HTTPS origin used in OAuth metadata, with no trailing slash. When it is missing, Aldhani uses the request host. |
| `oauthSubject` | Optional Domino name placed in the access token. When it is missing, `kyberUser` is used. Domino must be able to find that person. |

`aldhani.json`, `aldhani-oauth.pem`, `.env`, and `.cursor/mcp.json` hold passwords, tokens, and the OAuth signing key. They are gitignored. A later save of `aldhani.json` is read on the next call. A save that does not parse leaves the last good configuration in place, including the caller token.

## Call the endpoint

`POST /mcp` with `Content-Type: application/json` and an `Accept` of `application/json`, `text/event-stream`, or `*/*`. `GET` and `DELETE` return 405. A notification, a request with no `id`, returns 202 and an empty body. `initialize` returns `MCP-Session-Id`. Later requests may send it back. The design calls do not require it.

Accepted protocol versions are `2024-11-05`, `2025-03-26`, `2025-06-18`, and `2025-11-25`. Any other version is answered as `2025-11-25`. A later request with an `MCP-Protocol-Version` outside that list returns 400. A request whose `Origin` is present and is neither this host nor a loopback host returns 403.

`initialize` returns server instructions. Methods are `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`, `resources/list`, `resources/templates/list`, `resources/read`, `completion/complete`, `prompts/list`, `prompts/get`, and `logging/setLevel`. `notifications/cancelled` stops a call that is still running.

A successful tool result that is a JSON object is also returned as `structuredContent`. Tools whose result is a JSON object declare `outputSchema`. `export_design` does not, because its usual result is DXL text. Allowlisted databases are resources at `aldhani://<nsf>`. The NSF path may contain slashes, and the longest allowlisted path is the database. A collection is `aldhani://<nsf>/<type>`, a database note such as `acl` is that same shape, and one element is `aldhani://<nsf>/<type>/<name>`. Reading the database resource returns that database. `completion/complete` finishes `type`, `nsfPath`, and, when `type` is already set, `name`.

Prompts are `review_design` and `explain_design`. `create_element` is listed when `allowWrite` is true. Each one tells the agent which tool to call. They do not write by themselves. A `cursor` on `prompts/list` is an error, because the list is not paged.

`logging/setLevel` takes a syslog severity (`debug` through `emergency`). A tool call then emits `notifications/message` at that severity and above. A call that includes `_meta.progressToken` also emits `notifications/progress`. When `Accept` contains `text/event-stream`, those messages are written on the response as they happen, and the JSON-RPC result is the last event. A client that asks only for `application/json` still receives the result.

A missing or wrong token returns 401. The challenge is `Aldhani`, and also `Bearer` with `resource_metadata` pointing at `/.well-known/oauth-protected-resource/mcp`.

Two ways reach the servlet:

- `Authorization: Aldhani <token>` or `X-Aldhani-Token`, compared with `bearerToken`. This is the direct header. Domino session authentication does not consume it.
- OAuth 2.1 with PKCE. Discovery is under `/oauth`. The authorize page asks for the same `bearerToken`. The access token is an RS256 JWT, valid for 10 minutes, with scope `aldhani` and `Domino.user.all`. Domino's HTTP task only forwards `Authorization: Bearer` when that JWT passes its own OIDC check, so the internet site has to trust this server as an OIDC provider and have Bearer token authentication enabled. Until then, a client that sends Bearer gets Domino's login page. The signing key is `aldhani-oauth.pem` beside `aldhani.json`. It is created on first use and is not part of the jar.

The access token is a Domino bearer for `oauthSubject` (or `kyberUser`) for those 10 minutes. It is not limited to `/mcp`. The refresh token is opaque, lasts 12 hours, and cannot be used against the rest of the server. An authorization code lasts 2 minutes and can be exchanged once.

PKCE `S256` is required. The built-in client id `aldhani` may redirect to loopback or `cursor://`. Any other client registers at `POST /oauth/register` with `redirect_uris`. A redirect must be `https`, loopback, or `cursor://`.

| Path | Role |
| --- | --- |
| `GET /.well-known/oauth-protected-resource` | Resource metadata for the host. |
| `GET /.well-known/oauth-protected-resource/mcp` | Resource metadata for `/mcp`. The 401 challenge points here. |
| `GET /.well-known/oauth-authorization-server/oauth` | Authorization server metadata. |
| `GET /.well-known/openid-configuration/oauth` | The same metadata, for an issuer with a path. |
| `GET /oauth/.well-known/openid-configuration` | The same metadata, appended to the issuer. Domino's OIDC client reads this one. |
| `GET /oauth/jwks` | The RS256 public key. |
| `GET /oauth/authorize` | Asks for `bearerToken`, then redirects with a code. |
| `POST /oauth/token` | Exchanges a code or a refresh token. |
| `POST /oauth/register` | Dynamic client registration. |

Cursor, with the direct header, in `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "Aldhani": {
      "type": "http",
      "url": "https://dantooine.oninfoware.local/mcp",
      "placement": "client",
      "headers": {
        "Authorization": "Aldhani <token>"
      }
    }
  }
}
```

Replace the host. The header value is the same string as `bearerToken`.

## Tools

`tools/call` returns the Kyber body as a text content block. A Kyber error or a rejected argument sets `isError` to true. An unknown tool name does the same. An unknown MCP method is a JSON-RPC error `-32601`.

### list_databases

Kyber databases of the given `fileType` that are also on `allowedNsf`. The default `fileType` is `database`. Also accepted: `notes_database`, `template`, `replica_candidate`, `template_candidate`, and the Notes file-type numbers Kyber accepts (`1247`, `1248`, `1245`, `1246`).

The result has `allowedNsf`, `databases` (`nsfPath`, `replicaId`, `dbTitle`), and `missing`.

### list_design

Lists one collection in an allowed NSF. `type` is required. Singular and plural names both work (`form` and `forms`). `limit` keeps the first rows. When the collection is longer, the result has `truncated`, `returned`, and `total`.

| `type` | What Kyber lists |
| --- | --- |
| `forms` | Forms |
| `subforms` | Subforms |
| `pages` | Pages |
| `views` | Views |
| `folders` | Folders |
| `framesets` | Framesets |
| `outlines` | Outlines |
| `navigators` | Navigators |
| `agents` | Agents |
| `scriptlibraries` | Script libraries |
| `sharedfields` | Shared fields |
| `sharedcolumns` | Shared columns |
| `sharedactions` | Shared actions |
| `images` | Image resources |
| `fileresources` | File resources |
| `stylesheets` | Style sheets |
| `themes` | Themes |
| `dataconnections` | Data connections |
| `applets` | Applets |
| `javasources` | Java sources |
| `jars` | JARs |
| `webservices` | Web service providers |
| `webserviceconsumers` | Web service consumers |
| `compositeapplications` | Composite applications |
| `compositecomponents` | Composite components |
| `customcontrols` | Custom controls |
| `wiringproperties` | Wiring properties |
| `webcontent` | WebContent files |
| `xpages` | XPages |
| `xpagesresources` | XPages project files |

A database note is not a collection. Read it with `get_design`.

### get_design

Returns one Kyber JSON object, including its DXL.

- For a collection, `name` is the element name. XPages resource paths may contain a slash. `part` reads a large body in pages of raw text. The first page is `0`.
- For a database note, omit `name`. `type` is one of `about`, `acl`, `applicationproperties`, `databasescript`, `dbicon`, `using`, `xspproperties`.

### export_design

Exports the design of one allowed NSF as DXL. The body is large. Prefer `inventory_design`, then `get_design`, when the DXL bodies are not needed. `part` reads the raw export in pages.

### inventory_design

Lists every design element in one allowed NSF without DXL. Each row has `type`, `name` when present, `noteId`, and `unid`. Prefer this before `get_design`.

### repo_path

Returns the Package Explorer path for a design file. It does not call Kyber. Omit `type` to list every folder. `nsfPath` and `replicaId` are set together and produce `domino/<nsfPath>-<replicaId>`. `noteId` makes the file `<name>-<noteId>.dxl`. Singular and plural type names both work. Java sources keep their package under `Code/Java`. An XPages resource under `WEB-INF` is `WebContent/WEB-INF`.

### sync_repo

Reads `GET /export` and writes every element under `repoRoot`, replacing that NSF's folder. The database icon is `Resources/Images/$DBIcon-<noteId>.dxl`. Omit `nsfPath` to refresh every NSF in `repoNsf`. `create_design`, `update_design`, and `delete_design` do the same refresh for the NSF they changed, when it is in `repoNsf`. `validate_design` does not.

### create_database, create_design, update_design, delete_design, validate_design

Listed only when `allowWrite` is true. They call Kyber as `kyberUser`.

- `create_database` posts to `POST /database`. `nsfPath` must be on `allowedNsf`. `dbTitle` sets the title. Kyber sets the category `KyberEnabled` and gives the caller Manager. An existing file is left unchanged. Database delete is not a tool.
- `create_design` posts to the collection. `name` is required. Omit `dxl` to copy Kyber's starter. Agents, script libraries, and Java sources take `kind`. `about`, `using`, `databasescript`, and `xspproperties` can be created. `acl`, `applicationproperties`, and `dbicon` cannot.
- `update_design` puts DXL onto an existing element or database note. `acl` cannot be replaced. `applicationproperties` and `dbicon` can.
- `delete_design` deletes a named element, or `about`, `using`, `databasescript`, or `xspproperties`. `acl`, `applicationproperties`, and `dbicon` cannot be deleted.
- `validate_design` posts `{elements:[{type, name, dxl, delete}]}` to `POST /validate` and writes nothing. `dxl` is required unless `delete` is true.

Read tools are marked read-only. `delete_design` is marked destructive.

`notifications/cancelled` with the JSON-RPC `requestId` stops a design call that has not finished. The stopped call returns no result. A cancellation that arrives after the result is ignored.

## Smoke

`smoke.ps1` calls a running servlet. It checks anonymous and wrong-token rejection, the `Aldhani` challenge, `GET` and `DELETE` 405, ping, an unknown method, instructions, the prompts and logging capabilities, `review_design`, type completion, an allowlisted resource, `outputSchema` on `list_databases`, and the read-only and destructive hints. It reads the OAuth discovery document. It reads every collection and every database note in `aldhanitest.nsf`, including `SmokeForm`, `SmokeView`, the `SmokeAgent*` agents, and `SmokeXPage.xsp`. Each sampled element has a UNID on the list and on the get. `limit` on agents keeps the first row. `type=defaults` is refused. The export is either full DXL or a JSON object with `truncated` and `chars` and no DXL. `kybertest.nsf` is refused. When the write tools are listed, the run creates `AldhaniSmokeProbe`, updates it, refuses an ACL replace, validates a broken form without writing, and deletes the probe. When they are not listed, calling them is an error.

```powershell
.\smoke.ps1
.\smoke.ps1 -Url https://dantooine.oninfoware.local/mcp -Config C:\Domino\Data\aldhani.json
```

The script reads `bearerToken` from the config file and does not print it. It is not part of `build.ps1`.

## Layout

| Path | Role |
| --- | --- |
| `src/main/java/se/infoware/domino/aldhani/` | Servlet, MCP protocol, Kyber client, design tools |
| `src/main/resources/plugin.xml` | Equinox aliases for `/mcp` and the OAuth paths |
| `src/main/resources/feature.xml` | Feature installed into the applications site |
| `src/test/java/.../AldhaniSelfTest.java` | In-process checks run by `build.ps1` |
| `src/test/java/.../AldhaniSmoke.java` | Live checks run by `smoke.ps1` |
| `build.ps1` | Compile, self-test, and jar |
| `install.ps1` | Place the jar, feature, and `platform.xml` entry |
| `aldhani.json.example` | Configuration template |
| `LICENSE` | Apache License, Version 2.0 |

## License

Aldhani is open-source software licensed under the Apache License, Version 2.0. Copyright 2026 Infoware Solutions Svenska AB.

[Apache-2.0](LICENSE). Report vulnerabilities in [SECURITY.md](SECURITY.md), not in public issues.
