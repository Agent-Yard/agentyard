# Deploy Layout

`deploy/` is the single place for runtime composition and environment deployment templates.

```text
deploy/
  common/  shared, versioned, secret-free runtime materials
  local/   local development dependency compose
  dev/     shared development server compose
  test/    modular test deployment templates
```

`deploy/common` may be referenced by environment directories. When publishing an environment package to a target host, package that environment directory together with `deploy/common` if any compose file needs shared material.

Release environments such as `test` and future `prd` are deployed by module. Do not assume all services run on one host.
