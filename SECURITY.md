# Security policy

## Reporting a vulnerability

Report a security issue in Aldhani privately:

https://github.com/infoware-se/aldhani/security/advisories/new

Do not open a public GitHub issue for a security report.

Aldhani is an MCP servlet on Domino HTTP. It authenticates the MCP caller, then calls Kyber as `kyberUser`. A report should describe how a caller can reach a database outside `allowedNsf`, bypass the caller token, or do more than `kyberUser` is allowed to do through Kyber.

Include the Aldhani version, the MCP method or tool name, and whether `allowWrite` was enabled. Do not include passwords, bearer tokens, or a live server name.
