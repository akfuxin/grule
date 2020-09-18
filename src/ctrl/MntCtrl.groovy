package ctrl

import cn.xnatural.enet.event.EL
import core.Page
import core.ServerTpl
import core.Utils
import core.http.HttpContext
import core.http.mvc.ApiResp
import core.http.mvc.Ctrl
import core.http.mvc.Filter
import core.http.mvc.Path
import core.http.ws.Listener
import core.http.ws.WS
import core.http.ws.WebSocket
import core.jpa.BaseRepo
import dao.entity.*
import service.rule.DecisionManager
import service.rule.spec.DecisionSpec

import javax.persistence.criteria.Predicate
import java.util.concurrent.ConcurrentHashMap

@Ctrl(prefix = 'mnt')
class MntCtrl extends ServerTpl {

    @Lazy def repo = bean(BaseRepo, 'jpa_rule_repo')

    protected final Set<WebSocket> wss = ConcurrentHashMap.newKeySet()


    @Filter
    void filter(HttpContext ctx) {
        if (ctx.pieces?[0] !in ['login']) { // session 判断, login 不拦截
            def res = getCurrentUser(ctx)
            if (res.code != '00') { // 判断当前session 是否过期
                ctx.render(res)
            }
        }
    }


    @EL(name = 'wsMsg')
    void wsMsgBroadcast(String msg) { wss.each {ws -> ws.send(msg)} }

    @WS(path = 'ws')
    void receiveWs(WebSocket ws) {
        log.info('WS connect. {}', ws.session.sc.remoteAddress)
        ws.listen(new Listener() {

            @Override
            void onClose(WebSocket wst) {
                wss.remove(wst)
            }

            @Override
            void onText(String msg) {
                log.info('rule mnt ws receive client msg: {}', msg)
            }
        })
        wss.add(ws)
    }


    /**
     * 登录
     * @param username
     * @param password
     * @param ctx
     * @return
     */
    @Path(path = 'login')
    ApiResp login(String username, String password, HttpContext ctx) {
        if (!username) return ApiResp.fail('username must not be empty')
        if (!password) return ApiResp.fail('password must not be empty')
        ctx.setSessionAttr('name', username)
        ctx.setSessionAttr('id', username)
        ctx.setSessionAttr('uRoles', ['admin'] as Set)
        ApiResp.ok().attr('name', username)
    }


    /**
     * 获取当前 会话 中的用户信息
     * @param ctx
     * @return
     */
    @Path(path = 'getCurrentUser')
    ApiResp getCurrentUser(HttpContext ctx) {
        String name = ctx.getSessionAttr('name')
        if (name) {
            ApiResp.ok().attr('name', name)
        } else {
            ctx.response.status(401)
            ApiResp.fail('用户会话已失效, 请重新登录')
        }
    }


    @Path(path = 'decisionPage')
    ApiResp decisionPage(Integer page, String kw) {
        ApiResp.ok(
            repo.findPage(Decision, page, 10) {root, query, cb ->
                query.orderBy(cb.desc(root.get('updateTime')))
                if (kw) cb.like(root.get('dsl'), '%' + kw + '%')
            }
        )
    }


    @Path(path = 'fieldPage')
    ApiResp fieldPage(Integer page, String kw) {
        def fieldPage = Page.of(repo.findPage(RuleField, page, 10) { root, query, cb ->
            query.orderBy(cb.desc(root.get('updateTime')))
            if (kw) {
                cb.or(
                    cb.like(root.get('enName'), '%' + kw + '%'),
                    cb.like(root.get('cnName'), '%' + kw + '%'),
                    cb.like(root.get('comment'), '%' + kw + '%')
                )
            }
        }, { Utils.toMapper(it).build()})
        def collectorNames = fieldPage.list.collect {it.dataCollector}.findAll{it}
        if (collectorNames) {
            repo.findList(DataCollector) {root, query, cb -> root.get('enName').in(collectorNames)}.each {dc ->
                fieldPage.list.find {it.dataCollector == dc.enName}?.dataCollectorName = dc.cnName
            }
        }
        ApiResp.ok(fieldPage)
    }


    @Path(path = 'dataCollectorPage')
    ApiResp dataCollectorPage(Integer page, Integer pageSize, String kw) {
        if (pageSize && pageSize > 20) return ApiResp.fail("pageSize max 20")
        ApiResp.ok(
            repo.findPage(DataCollector, page, pageSize?:10) { root, query, cb ->
                query.orderBy(cb.desc(root.get('updateTime')))
                if (kw) {
                    cb.or(
                        cb.like(root.get('enName'), '%' + kw + '%'),
                        cb.like(root.get('cnName'), '%' + kw + '%'),
                        cb.like(root.get('comment'), '%' + kw + '%')
                    )
                }
            }
        )
    }


    @Path(path = 'opHistoryPage')
    ApiResp opHistoryPage(Integer page, Integer pageSize, String kw, String type) {
        if (pageSize && pageSize > 20) return ApiResp.fail("pageSize max 20")
        ApiResp.ok(
            repo.findPage(OpHistory, page, pageSize?:10) { root, query, cb ->
                query.orderBy(cb.desc(root.get('createTime')))
                def ps = []
                if (kw) {
                    ps << cb.like(root.get('content'), '%' + kw + '%')
                }
                if (type) {
                    ps << cb.equal(root.get('tbName'), repo.tbName(Decision.package.name + "." + type))
                }
                cb.and(ps.toArray(new Predicate[ps.size()]))
            }
        )
    }


    /**
     * 设置一条决策
     * @param id 决策数据库中的Id 如果为空, 则为新建
     * @param dsl 决策DSL
     * @return
     */
    @Path(path = 'setDecision', method = 'post')
    ApiResp setDecision(String id, String dsl, HttpContext ctx) {
        DecisionSpec spec
        try {
            spec = bean(DecisionManager).create(dsl)
        } catch (ex) {
            log.error("语法错误", ex)
            return ApiResp.fail('语法错误: ' + ex.message)
        }
        Decision decision
        if (id) { // 更新
            ctx.auth('decision-update')
            decision = repo.findById(Decision, id)
            if (decision.decisionId != spec.决策id) {
                if (repo.find(Decision) {root, query, cb -> cb.equal(root.get('decisionId'), spec.决策id)}) { // 决策id 不能重
                    return ApiResp.fail("决策id($spec.决策id)已存在")
                }
            }
        } else { // 创建
            ctx.auth('decision-create')
            decision = new Decision()
        }
        decision.decisionId = spec.决策id
        decision.name = spec.决策名
        decision.comment = spec.决策描述
        decision.dsl = dsl
        repo.saveOrUpdate(decision)
        ep.fire('enHistory', decision, ctx.getSessionAttr('name'))
        ApiResp.ok(decision)
    }


    @Path(path = 'addField', method = 'post')
    ApiResp addField(HttpContext ctx, String enName, String cnName, FieldType type, String comment, String dataCollector) {
        ctx.auth('field-add')
        if (!enName) return ApiResp.fail("enName must not be empty")
        if (!cnName) return ApiResp.fail("cnName must not be empty")
        if (!type) return ApiResp.fail("type must not be empty")
        if (repo.count(RuleField) {root, query, cb -> cb.equal(root.get('enName'), enName)}) return ApiResp.fail("$enName aleady exist")
        if (repo.count(RuleField) {root, query, cb -> cb.equal(root.get('cnName'), cnName)}) return ApiResp.fail("$cnName aleady exist")
        def field = new RuleField(enName: enName, cnName: cnName, type: type, comment: comment, dataCollector: dataCollector)
        repo.saveOrUpdate(field)
        ep.fire('addField', field.enName)
        ep.fire('enHistory', field, ctx.getSessionAttr('name'))
        ApiResp.ok(field)
    }


    @Path(path = 'addDataCollector', method = 'post')
    ApiResp addDataCollector(HttpContext ctx) {
        ctx.auth('dataCollector-add')
        def map = ctx.params()
        DataCollector dc = new DataCollector()
        map.each {entry -> if (dc.hasProperty(entry.key)) dc.setProperty(entry.key, entry.value)}
        if (!dc.enName) return ApiResp.fail('enName must not be empty')
        if (!dc.cnName) return ApiResp.fail('cnName must not be empty')
        if (!dc.type) return ApiResp.fail('type must not be empty')
        if ('http' == dc.type) {
            if (!dc.url) return ApiResp.fail('url must not be empty')
            if (!dc.method) return ApiResp.fail('method must not be empty')
            if (!dc.contentType) return ApiResp.fail('contentType must not be empty')
        } else if ('script' == dc.type) {
            if (!dc.computeScript) return ApiResp.fail('computeScript must not be empty')
        }
        if (repo.find(DataCollector) {root, query, cb -> cb.equal(root.get('enName'), dc.enName)}) {
            return ApiResp.fail("$dc.enName 已存在")
        }
        if (repo.find(DataCollector) {root, query, cb -> cb.equal(root.get('cnName'), dc.cnName)}) {
            return ApiResp.fail("$dc.cnName 已存在")
        }
        repo.saveOrUpdate(dc)
        ep.fire('addDataCollector', dc.enName)
        ep.fire('enHistory', dc, ctx.getSessionAttr('name'))
        ApiResp.ok(dc)
    }


    @Path(path = 'updateField', method = 'post')
    ApiResp updateField(HttpContext ctx, Long id, String enName, String cnName, FieldType type, String comment, String dataCollector) {
        ctx.auth('field-update')
        if (!id) return ApiResp.fail("id not legal")
        if (!enName) return ApiResp.fail("enName must not be empty")
        if (!cnName) return ApiResp.fail("cnName must not be empty")
        if (!type) return ApiResp.fail("type must not be empty")
        def field = repo.findById(RuleField, id)
        if (field == null) return ApiResp.fail("id: $id not found")
        if (enName != field.enName && repo.count(RuleField) {root, query, cb -> cb.equal(root.get('enName'), enName)}) {
            return ApiResp.fail("$enName aleady exist")
        }
        if (cnName != field.cnName && repo.count(RuleField) {root, query, cb -> cb.equal(root.get('cnName'), cnName)}) {
            return ApiResp.fail("$cnName aleady exist")
        }

        field.enName = enName
        field.cnName = cnName
        field.type = type
        field.comment = comment
        field.dataCollector = dataCollector

        repo.saveOrUpdate(field)
        ep.fire('updateField', field.enName)
        ep.fire('enHistory', field, ctx.getSessionAttr('name'))
        ApiResp.ok(field)
    }


    @Path(path = 'updateDataCollector', method = 'post')
    ApiResp updateDataCollector(HttpContext ctx, Long id, String enName, String cnName, String url, String bodyStr, String method, String parseScript, String contentType, String comment, String computeScript) {
        ctx.auth('dataCollector-update')
        if (!id) return ApiResp.fail("id not legal")
        if (!enName) return ApiResp.fail("enName must not be empty")
        if (!cnName) return ApiResp.fail("cnName must not be empty")
        def collector = repo.findById(DataCollector, id)
        if (collector == null) return ApiResp.fail("id: $id not found")
        if ('http' == collector.type) {
            if (!url) return ApiResp.fail('url must not be empty')
            if (!method) return ApiResp.fail('method must not be empty')
            if (!contentType) return ApiResp.fail('contentType must not be empty')
            collector.url = url
            collector.method = method
            collector.contentType = contentType
            collector.bodyStr = bodyStr
            collector.parseScript = parseScript?.trim()
            if (collector.parseScript && (!collector.parseScript.startsWith('{') || !collector.parseScript.endsWith('}'))) {
                return ApiResp.fail('parseScript is not a function')
            }
        } else if ('script' == collector.type) {
            if (!computeScript) return ApiResp.fail('computeScript must not be empty')
            collector.computeScript = computeScript?.trim()
            if (collector.computeScript && (collector.computeScript.startsWith('{') || collector.computeScript.endsWith('}'))) {
                return ApiResp.fail('computeScript is pure script. cannot startWith { or endWith }')
            }
        }
        def updateRelateField
        if (enName != collector.enName ) {
            if (repo.count(DataCollector) {root, query, cb -> cb.equal(root.get('enName'), enName)}) {
                return ApiResp.fail("$enName aleady exist")
            }
            collector.enName = enName
            updateRelateField = { // 修改RuleField相关联
                repo.find(RuleField) {root, query, cb -> cb.equal(root.get('dataCollector'), collector.cnName)}.each {field ->
                    field.dataCollector = enName
                    repo.saveOrUpdate(field)
                }
            }
        }
        if (cnName != collector.cnName) {
            if (repo.count(DataCollector) {root, query, cb -> cb.equal(root.get('cnName'), cnName)}) {
                return ApiResp.fail("$cnName aleady exist")
            }
            collector.cnName = cnName
        }
        collector.comment = comment

        if (updateRelateField) {
            repo.trans {
                repo.saveOrUpdate(collector)
                updateRelateField()
            }
        } else {
            repo.saveOrUpdate(collector)
        }
        ep.fire('updateDataCollector', collector.enName)
        ep.fire('enHistory', collector, ctx.getSessionAttr('name'))
        ApiResp.ok(collector)
    }


    @Path(path = 'delDecision/:decisionId')
    ApiResp delDecision(HttpContext ctx, String decisionId) {
        repo.delete(repo.find(Decision) {root, query, cb -> cb.equal(root.get('decisionId'), decisionId)})
        ctx.auth('decision-del')
        ep.fire('delDecision', decisionId)
        ApiResp.ok()
    }


    @Path(path = 'delField/:enName')
    ApiResp delField(HttpContext ctx, String enName) {
        ctx.auth('field-del')
        repo.delete(repo.find(RuleField) {root, query, cb -> cb.equal(root.get('enName'), enName)})
        ep.fire('delField', enName)
        ApiResp.ok()
    }


    @Path(path = 'delDataCollector/:enName')
    ApiResp delDataCollector(HttpContext ctx, String enName) {
        ctx.auth('dataCollector-del')
        repo.delete(repo.find(DataCollector) {root, query, cb -> cb.equal(root.get('enName'), enName)})
        ep.fire('delDataCollector', enName)
        ApiResp.ok()
    }
}
