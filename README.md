# Aldhani

Aldhani är en OSGi-servlet på Dominos HTTP-task. Den tar emot MCP och läser design genom [Kyber](https://github.com/infoware-se) på DRAPI. Själva designändringarna stannar i Kyber. Den här versionen är bara läsning.

Sandbox-databasen är `aldhanitest.nsf`. Den ska ha katalogkategorin `KyberEnabled`.

## Bygg

Kräver JDK 21 och Dominos servlet-API (`com.ibm.pvc.servlet`). `build.ps1` använder Dominos `java` för självtestet och faller tillbaka till `javac` på `PATH` om Dominos JVM saknar kompilator.

```powershell
.\build.ps1
```

Det ger

- `target\infoware-aldhani-0.1.0.jar`
- `target\se.infoware.domino.aldhani.feature_0.1.0.jar`

## Installera

Kopiera plugin-jarren och packa upp featuren. Equinox på HTTP-tasken läser features som kataloger, och sajten `applications` har policyn `USER-EXCLUDE`, så featuren måste stå i `platform.xml`.

```text
<Domino data>\domino\workspace\applications\eclipse\plugins\se.infoware.domino.aldhani_0.1.0.jar
<Domino data>\domino\workspace\applications\eclipse\features\se.infoware.domino.aldhani.feature_0.1.0\feature.xml
```

Lägg in featuren under applications-sajten i `domino\workspace\.config\org.eclipse.update\platform.xml`:

```xml
<feature id="se.infoware.domino.aldhani.feature" url="features/se.infoware.domino.aldhani.feature_0.1.0/" version="0.1.0">
</feature>
```

Gör det medan HTTP är stoppad. `tell http restart` laddar inte om OSGi-konfigurationen. Stoppa och starta bara HTTP-tasken:

```text
tell http quit
load http
```

Starta inte om Windows-tjänsten.

Kopiera `aldhani.json.example` till `<Domino data>\aldhani.json` och fyll i lösenord och token. Servletten läser om filen när den ändras. En ny jar kräver däremot att HTTP startas om.

`ALDHANI_CONFIG` kan peka på en annan konfigurationsfil.

## Anrop

Endpointen är `POST /mcp`. `GET` svarar 405.

Domino HTTP med sessionsinloggning tar `Authorization: Basic` och `Authorization: Bearer` och svarar med inloggningssidan innan servletten ser anropet. Skicka token som `Authorization: Aldhani <token>`. Samma värde som `bearerToken` i `aldhani.json`. `X-Aldhani-Token` fungerar också.

Cursor, i `.cursor/mcp.json` (filen är gitignorerad):

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

## Verktyg

| Verktyg | Gör |
| --- | --- |
| `list_databases` | Kyber-databaser som finns på allowlistan. Övriga döljs. Saknade namn listas under `missing`. |
| `list_design` | Formulär, vyer, agenter eller XPages. `type` är `form`, `view`, `agent` eller `xpage`. |
| `get_design` | Ett designelement som Kyber-JSON, inklusive DXL. |
| `export_design` | Hela designen som DXL. |

`nsfPath` behövs bara när allowlistan har mer än en databas. Sökvägar med `..`, inledande `/` eller `:` avvisas innan Kyber anropas.

## Det som inte följer med i git

`aldhani.json`, `.env` och `.cursor/mcp.json` innehåller lösenord och token. De ligger i `.gitignore`.
