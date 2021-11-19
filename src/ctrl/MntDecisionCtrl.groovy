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
import service.rule.CollectorManager
import service.rule.spec.DecisionSpec

@Ctrl(prefix = 'mnt/decision')
class MntDecisionCtrl extends ServerTpl {

    @Lazy protected repo = bean(Repo, 'jpa_rule_repo')
    @Lazy protected collectorManager = bean(CollectorManager)


    @Path(path = 'page')
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


    /**
     * 添加一条决策
     * @param dsl 决策DSL
     * @param apiConfig api配置
     * @param hCtx {@link HttpContext}
     */
    @Path(path = '/', method = 'put')
    ApiResp add(HttpContext hCtx, String dsl, String apiConfig) {
        if (!dsl) return ApiResp.fail("Param dsl required")
        hCtx.auth('decision-add')
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

        final decision = new Decision(
                decisionId: spec.决策id, name: spec.决策名, comment: spec.决策描述,
                dsl: dsl, creator: hCtx.getSessionAttr("uName")
        )
        adjustApiConfig(decision, apiConfig)

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
        ep.fire("decision.create", decision.id)
        ep.fire('enHistory', decision, decision.creator)
        ApiResp.ok(decision)
    }


    @Path(path = "updateDsl/:id", method = "post")
    ApiResp updateDsl(HttpContext hCtx, String id, String dsl) {
        if (!id) return ApiResp.fail("Param id required")
        if (!dsl) return ApiResp.fail("Param dsl required")
        def decision = repo.findById(Decision, id)
        if (!decision) return ApiResp.fail("Param id not found")
        hCtx.auth('decision-update-' + id)

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
        decision.dsl = dsl
        decision.name = spec.决策名
        decision.decisionId = spec.决策id
        decision.comment = spec.决策描述
        decision.updater = hCtx.getSessionAttr('uName')

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

        ep.fire("decision.update", decision.id)
        ep.fire('enHistory', decision, decision.updater)
        ApiResp.ok(decision)
    }


    @Path(path = "updateApiConfig/:id", method = "post")
    ApiResp updateApiConfig(HttpContext hCtx, String id, String apiConfig) {
        if (!id) return ApiResp.fail("Param id required")
        def decision = repo.findById(Decision, id)
        if (!decision) return ApiResp.fail("Param id not found")
        hCtx.auth('decision-update-' + id)

        adjustApiConfig(decision, apiConfig)
        decision.updater = hCtx.getSessionAttr('uName')
        repo.saveOrUpdate(decision)

        ep.fire("decision.update", decision.id)
        ep.fire('enHistory', decision, decision.updater)
        ApiResp.ok(decision)
    }


    @Path(path = ':id', method = "delete")
    ApiResp del(HttpContext hCtx, String id) {
        if (!id) return ApiResp.fail("Param id required")
        def decision = repo.findById(Decision, id)
        if (!decision) return ApiResp.fail("Param id not found")
        hCtx.auth( 'decision-del-' + id)
        repo.delete(decision)

        ep.fire('decision.delete', decision.id)
        // ep.fire('enHistory', decision, hCtx.getSessionAttr('uName'))
        ApiResp.ok()
    }


    // 更新 适配 api配置 参数
    protected void adjustApiConfig(Decision decision, String apiConfig) {
        if (apiConfig) {
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
            decision.apiConfig = params.toString()
        } else {
            decision.apiConfig = new JSONArray().add(
                    new JSONObject().fluentPut("code", "decisionId").fluentPut("type", "Str")
                            .fluentPut("fixValue", decision.decisionId).fluentPut("name", "决策id").fluentPut("require", true)
            ).toString()
        }
    }
}
