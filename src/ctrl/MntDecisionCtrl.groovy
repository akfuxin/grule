package ctrl

import cn.xnatural.app.ServerTpl
import cn.xnatural.app.Utils
import cn.xnatural.http.ApiResp
import cn.xnatural.http.Ctrl
import cn.xnatural.http.HttpContext
import cn.xnatural.http.Path
import cn.xnatural.jpa.Page
import cn.xnatural.jpa.Repo
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import entity.Decision
import entity.OpHistory
import service.rule.CollectorManager
import service.rule.DecisionManager
import service.rule.FieldManager
import service.rule.spec.DecisionSpec

import java.text.SimpleDateFormat

@Ctrl(prefix = 'mnt')
class MntDecisionCtrl extends ServerTpl {

    @Lazy protected repo = bean(Repo, 'jpa_rule_repo')
    @Lazy protected collectorManager = bean(CollectorManager)


    @Path(path = 'decisionPage')
    ApiResp decisionPage(HttpContext hCtx, Integer page, Integer pageSize, String kw, String nameLike, String decisionId) {
        if (pageSize && pageSize > 20) return ApiResp.fail("Param pageSize <=20")
        // 允许访问的决策id
        def ids = hCtx.getAttr("permissions", Set)
            .findAll {String p -> p.startsWith("decision-read-")}
            .findResults {String p -> p.replace("decision-read-", "")}
            .findAll {it}
        if (!ids) return ApiResp.ok(Page.empty())
        def delIds = hCtx.getAttr("permissions", Set)
            .findAll {String p -> p.startsWith("decision-del-")}
            .findResults {String p -> p.replace("decision-del-", "")}
            .findAll {it}
        def updateIds = hCtx.getAttr("permissions", Set)
            .findAll {String p -> p.startsWith("decision-update-")}
            .findResults {String p -> p.replace("decision-update-", "")}
            .findAll {it}
        ApiResp.ok(
                repo.findPage(Decision, page, pageSize?:10) {root, query, cb ->
                    query.orderBy(cb.desc(root.get('updateTime')))
                    def ps = []
                    ps << root.get("id").in(ids)
                    if (decisionId) ps << cb.equal(root.get('id'), decisionId)
                    if (nameLike) ps << cb.or(cb.like(root.get('name'), '%' + nameLike + '%'), cb.like(root.get('decisionId'), '%' + nameLike + '%'))
                    if (kw) ps << cb.like(root.get('dsl'), '%' + kw + '%')
                    ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
                }.to{decision ->
                    Utils.toMapper(decision)
                            .add("_deletable", delIds?.contains(decision.id))
                            .add("_readonly", !updateIds?.contains(decision.id))
                            .build()
                }
        )
    }


    @Path(path = 'opHistoryPage')
    ApiResp opHistoryPage(HttpContext hCtx, Integer page, Integer pageSize, String kw, String type, String startTime, String endTime) {
        if (pageSize && pageSize > 20) return ApiResp.fail("Param pageSize <=20")
        hCtx.auth("opHistory-read")
        Date start = startTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(endTime) : null
        ApiResp.ok(
            repo.findPage(OpHistory, page, pageSize?:5) { root, query, cb ->
                query.orderBy(cb.desc(root.get('createTime')))
                def ps = []
                if (kw) ps << cb.like(root.get('content'), '%' + kw + '%')
                if (start) ps << cb.greaterThanOrEqualTo(root.get('createTime'), start)
                if (end) ps << cb.lessThanOrEqualTo(root.get('createTime'), end)
                if (type) {
                    ps << cb.equal(root.get('tbName'), repo.tbName(Class.forName(Decision.package.name + "." + type)).replace("`", ""))
                }
                ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
            }
        )
    }


    /**
     * 设置一条决策
     * @param id 决策数据库中的Id 如果为空, 则为新建
     * @param dsl 决策DSL
     * @param apiConfig api配置
     * @param hCtx {@link HttpContext}
     */
    @Path(path = 'setDecision', method = 'post')
    ApiResp setDecision(String id, String dsl, String apiConfig, HttpContext hCtx) {
        if (!dsl) return ApiResp.fail("Param dsl not empty")
        DecisionSpec spec
        try {
            spec = DecisionSpec.of(dsl)
        } catch (ex) {
            log.error("语法错误", ex)
            return ApiResp.fail('语法错误: ' + ex.message)
        }

        //dsl 验证
        if (!spec.决策id) return ApiResp.fail("决策id 不能为空")
        if (!spec.决策名) return ApiResp.fail("决策名 不能为空")

        Decision decision
        if (id) { // 更新
            decision = repo.findById(Decision, id)
            hCtx.auth('decision-update-' + decision.id)
            if (decision.decisionId != spec.决策id) {
                if (repo.find(Decision) {root, query, cb -> cb.equal(root.get('decisionId'), spec.决策id)}) { // 决策id 不能重
                    return ApiResp.fail("决策id($spec.决策id)已存在")
                }
            }
            decision.updater = hCtx.getSessionAttr("uName")
        } else { // 创建
            hCtx.auth('decision-add')
            decision = new Decision()
            decision.creator = hCtx.getSessionAttr("uName")
        }
        decision.decisionId = spec.决策id
        decision.name = spec.决策名
        decision.comment = spec.决策描述
        decision.dsl = dsl
        if (apiConfig) { //矫正decisionId参数
            def params = JSON.parseArray(apiConfig)
            JSONObject param = params.find {JSONObject jo -> "decisionId" == jo.getString("code")}
            if (param) {
                param.fluentPut("fixValue", decision.decisionId).fluentPut("type", "Str").fluentPut("require", true)
            } else {
                params.add(0, new JSONObject().fluentPut("code", "decisionId").fluentPut("type", "Str")
                        .fluentPut("fixValue", decision.decisionId).fluentPut("name", "决策id").fluentPut("require", true)
                )
            }
            for (def itt = params.iterator(); itt.hasNext(); ) {
                JSONObject jo = itt.next()
                if (!jo.getString("code") || !jo.getString("name")) itt.remove()
                for (def ittt = jo.iterator(); ittt.hasNext(); ) {
                    if (ittt.next().key.startsWith('_')) ittt.remove()
                }
            }
            apiConfig = params.toString()
        } else {
            apiConfig = new JSONArray().add(
                new JSONObject().fluentPut("code", "decisionId").fluentPut("type", "Str")
                    .fluentPut("fixValue", decision.decisionId).fluentPut("name", "决策id").fluentPut("require", true)
            ).toString()
        }
        decision.apiConfig = apiConfig

        try {
            repo.saveOrUpdate(decision)
        } catch (ex) {
            def cause = ex
            while (cause != null) {
                if (cause.message.contains("Duplicate entry")) {
                    if (cause.message.contains("decisionId")) {
                        return ApiResp.fail("决策id($spec.决策id)已存在")
                    }
                }
                cause = cause.cause
            }
            throw ex
        }
        ep.fire(id ? "decision.update" : "decision.create", decision.id)
        ep.fire('enHistory', decision, hCtx.getSessionAttr('uName'))
        ApiResp.ok(decision)
    }



    @Path(path = 'delDecision/:id')
    ApiResp delDecision(HttpContext hCtx, String id) {
        if (!id) return ApiResp.fail("Param id required")
        hCtx.auth( 'decision-del-' + id)
        def decision = repo.find(Decision) {root, query, cb -> cb.equal(root.get('id'), id)}
        repo.delete(decision)
        ep.fire('decision.delete', decision.id)
        ep.fire('enHistory', decision, hCtx.getSessionAttr('uName'))
        ApiResp.ok()
    }
}
