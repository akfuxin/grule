package service.rule

import cn.xnatural.app.CacheSrv
import cn.xnatural.app.ServerTpl
import cn.xnatural.enet.event.EL
import cn.xnatural.jpa.Repo
import entity.Decision
import entity.Permission
import entity.User

class UserSrv extends ServerTpl {

    @Lazy def repo = bean(Repo, 'jpa_rule_repo')
    /**
     * 默认静态权限
     */
    @Lazy def staticPermission = [
          new Permission(enName: 'grant', cnName: '权限管理'),
          new Permission(enName: 'grant-user', cnName: '用户管理'),
          new Permission(enName: 'mnt-login', cnName: '用户登陆'),
          new Permission(enName: 'user-add', cnName: '新增用户'),
          new Permission(enName: 'user-del', cnName: '删除用户'),
          // new Permission(enName: 'password-reset', cnName: '密码重置'),
          new Permission(enName: 'decision-add', cnName: '决策创建'),
          new Permission(enName: 'field-read', cnName: '查看字段'),
          new Permission(enName: 'field-add', cnName: '新增字段'),
          new Permission(enName: 'field-update', cnName: '更新字段'),
          new Permission(enName: 'field-del', cnName: '删除字段'),
          new Permission(enName: 'dataCollector-read', cnName: '查看收集器'),
          new Permission(enName: 'dataCollector-add', cnName: '新增收集器'),
          new Permission(enName: 'dataCollector-update', cnName: '更新收集器'),
          new Permission(enName: 'dataCollector-del', cnName: '删除收集器'),
          new Permission(enName: 'opHistory-read', cnName: '查看操作历史'),
          new Permission(enName: 'decideResult-read', cnName: '查看决策结果'),
          new Permission(enName: 'collectResult-read', cnName: '查看收集记录')
    ]


    // 一个决策对应的所有权限
    protected List<Permission> getPermissions(Decision decision) {
        [
          new Permission(enName: "decision-update-" + decision.id, cnName: "更新决策:" + decision.name, mark: decision.id, comment: "动态权限-决策"),
          new Permission(enName: "decision-del-" + decision.id, cnName: "删除决策:" + decision.name, mark: decision.id, comment: "动态权限-决策"),
          new Permission(enName: "decision-read-" + decision.id, cnName: "查看决策:" + decision.name, mark: decision.id, comment: "动态权限-决策")
        ]
    }


    /**
     * 初始化 权限数据
     */
    @EL(name = "jpa_rule.started", async = true)
    protected void initUserPermission() {
        staticPermission.each { p ->
            // ConstraintViolationException
            if (!repo.count(Permission) { root, query, cb -> cb.equal(root.get("enName"), p.enName) }) {
                repo.saveOrUpdate(p)
                log.info("添加默认静态权限: " + p.enName + ", " + p.cnName)
            }
        }

        // 添加历史未添加的权限
        for (int i = 0, limit = 10; ; i++) {
            def ls = repo.findList(Decision, i*limit, limit)
            async {
                ls.each { decision ->
                    if (!repo.count(Permission) {root, query, cb -> cb.equal(root.get("mark"), decision.id)}) { // 决策权限不存在,则创建
                        getPermissions(decision).each {repo.saveOrUpdate(it)}
                        log.info("添加决策权限'$decision.name($decision.id)'".toString())
                    }
                }
            }
            if (ls.size() < limit) break
        }

        // 初始化默认用户
        if (!repo.count(User)) {
            [
                new User(name: 'admin', password: 'admin'.md5(), group: 'admin')
            ].each {u ->
                u.permissions = staticPermission.collect {it.enName}.join(",")
                repo.saveOrUpdate(u)
                log.info("添加默认用户. " + u.name + ", " + u.password)
            }
        }
    }


    // 决策创建时 更新用户权限
    @EL(name = "decision.create")
    protected void decisionCreate(String id) {
        def decision = repo.findById(Decision, id)
        def ps = getPermissions(decision)

        // 创建新权限
        ps.each {repo.saveOrUpdate(it)}

        if (!decision.creator) return

        // 添加创建者的权限
        def user = repo.find(User) {root, query, cb -> cb.equal(root.get("name"), decision.creator)}
        LinkedHashSet alreadyPs = new LinkedHashSet(user.permissions?.split(",")?.toList()?:Collections.emptyList())
        if (!alreadyPs.containsAll(ps.findResults {it.enName})) {
            ps.each {alreadyPs.add(it.enName)}
            user.permissions = alreadyPs.join(",")
            repo.saveOrUpdate(user)
            bean(CacheSrv).remove("permission_" + user.id)
        }

        // 如果创建者是普通用户, 则需要把所有权限给所在的组管理员
        if (user.group) {
            async {
                def gUser = repo.find(User) {root, query, cb -> cb.and(cb.equal(root.get("group"), user.group), cb.like(root.get("permissions"), "%grant-user%"))}
                if (!gUser) return
                LinkedHashSet ls = new LinkedHashSet(gUser.permissions?.split(",")?.toList()?:Collections.emptyList())
                ps.each {ls.add(it.enName)}
                gUser.permissions = ls.join(",")
                repo.saveOrUpdate(gUser)
                bean(CacheSrv)?.remove("permission_" + gUser.id)
            }
        }
    }

    // 更新决策关联的权限名
    @EL(name = "decision.update", async = true)
    protected void decisionUpdate(String id) {
        def decision = repo.findById(Decision, id)
        def ps = getPermissions(decision)
        repo.findList(Permission) { root, query, cb -> cb.equal(root.get("mark"), id)}
                ?.each {p ->
                    p.cnName = ps.find {p.enName == it.enName}.cnName
                    repo.saveOrUpdate(p)
                }
    }

    @EL(name = "decision.delete", async = true)
    protected void decisionDelete(String id) {
        def ps = repo.findList(Permission) { root, query, cb -> cb.equal(root.get("mark"), id)}
                .each {repo.delete(it)}
        // 删除用户关联的权限
        for (int start = 0, limit = 10;; start++) {
            def users = repo.findList(User, start * limit, limit) {root, query, cb ->
                ps.findResults {cb.like(root.get("permissions"), "%" + it.enName + "%")}
                        .inject {p1, p2 -> cb.or(p1, p2)}
            }
            users.each {u ->
                u.permissions = u.permissions.split(",").findAll {!ps.contains(it)}.join(",")
                repo.saveOrUpdate(u)
                bean(CacheSrv)?.remove("permission_" + u.id)
            }
            if (users.size() < limit) break
        }
    }
}
