# P2.4 认证体系演进记录

当前阶段已从“bootstrap 固定用户”演进到 “OIDC + API 托管会话”的正式登录流，同时保留本地开发态 bootstrap 旁路。

## 当前状态

- API 新增 `platform_user` 与 `platform_user_role_binding`
- 默认引导一个本地 bootstrap admin 用户（默认用户名为 `admin`），授予 `PLATFORM_ADMIN`
- API 已接入 Spring Security + OAuth2 Client，请求级鉴权由统一 security filter chain 承担
- 新增 `GET /api/auth/login`、`POST /api/auth/logout`，浏览器通过 HttpOnly session cookie 访问 `/api`
- `/api/auth/session` 只在已认证时返回当前平台用户会话；未认证时返回 `401`
- API 启动时会同步本地 bootstrap admin 账户，确保其用户名与 `lynxus.auth.bootstrap.username` 配置一致
- 外部身份已扩展为 `issuer + subject`，本地用户 provisioning 按 `(external_issuer, external_subject)` 识别唯一身份
- `CurrentUserResolver`、`ExternalIdentityValidator`、`UserProvisioningService` 已用于真实 OIDC 登录链路
- API 已启用 method security；治理类写接口通过可复用 RBAC 注解收敛到 `PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER`
- `BUSINESS_USER` 保留运行态访问与目录只读，不允许目录治理写操作
- API、Worker、Agent Runtime、Knowledge Service 的内部 HTTP 调用统一要求 `Authorization: Bearer <LYNXUS_INTERNAL_AUTH_TOKEN>`
- 新增认证配置：
  - `lynxus.auth.bootstrap.username`
  - `lynxus.auth.default-role`
  - `lynxus.auth.dev-bootstrap-enabled`
  - `lynxus.auth.login-success-path`
  - `lynxus.internal-auth.token`

## 当前边界

- 不内置特定 IdP，部署环境需自行提供标准 OIDC provider
- 前端不保存 access token；当前仅支持 API 托管会话模式
- 不做域级授权、对象级授权与更细粒度 RBAC
- 运行态中的 `requester` 继续表示业务发起人，不与平台登录身份打通
- Python 内部服务不走 OIDC，只校验共享 internal token

## 后续扩展点

1. 对接企业 OIDC provider discovery、client registration 与登出策略
2. 在登录成功链路中追加 claim/group 到平台角色的映射策略
3. 让 `CurrentUserResolver` 进一步下沉为控制器/服务层统一的平台用户上下文
4. 将共享 internal token 演进为可审计的 service account / caller 级鉴权
