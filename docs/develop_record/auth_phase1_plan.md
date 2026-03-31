# P2.4 第一阶段落地说明

本阶段只补齐“正式用户来源”和“后续外部认证扩展点”，不启用真实登录流。

## 已落地

- API 新增 `platform_user` 与 `platform_user_role_binding`
- 默认引导一个本地 bootstrap admin 用户（默认用户名为 `admin`），授予 `PLATFORM_ADMIN`
- `/api/auth/session` 改为读取数据库中的当前平台用户会话
- API 启动时会同步本地 bootstrap admin 账户，确保其用户名与 `lynxus.auth.bootstrap.username` 配置一致
- 新增 `CurrentUserResolver`、`ExternalIdentityValidator`、`UserProvisioningService` 作为后续扩展骨架
- 新增认证配置：
  - `lynxus.auth.bootstrap.username`
  - `lynxus.auth.default-role`

## 当前边界

- 不提供登录页、登录接口、登出接口
- 不接入 Spring Security、OIDC 跳转、Header/Cookie/Token 鉴权
- 不做控制器级权限拦截
- 不做域级授权与细粒度 RBAC
- 运行态中的 `requester` 继续表示业务发起人，不与平台登录身份打通

## 后续启用真实外部认证时的接入点

1. 在请求链路中解析上游身份凭证
2. 调用 `ExternalIdentityValidator` 对接外部校验接口
3. 使用 `UserProvisioningService` 创建或更新本地用户，并授予默认角色
4. 将 `CurrentUserResolver` 从 bootstrap 固定用户切换为真实请求上下文解析
