package ctrl

import cn.xnatural.app.ServerTpl
import cn.xnatural.http.ApiResp
import cn.xnatural.http.Ctrl
import cn.xnatural.http.HttpContext
import cn.xnatural.http.Path
import cn.xnatural.jpa.Repo
import entity.DecisionResult
import service.rule.*

@Ctrl
class RuleCtrl extends ServerTpl {

    @Lazy def decisionSrv  = bean(DecisionSrv)
    @Lazy def dm           = bean(DecisionManager)
    @Lazy def fieldManager = bean(FieldManager)
    @Lazy def repo         = bean(Repo, 'jpa_rule_repo')


    /**
     * 执行一条决策
     */
    @Path(path = 'decision')
    ApiResp decision(String decisionId, HttpContext ctx) {
        if (!decisionId) {
            return ApiResp.fail('Param decisionId not empty')
        }
        def decisionHolder = dm.findDecision(decisionId)
        if (decisionHolder == null) {
            return ApiResp.fail("未找到决策: $decisionId")
        }
        Map<String, Object> params = ctx.params()
        log.info("Run decision. decisionId: " + decisionId + ", id: " + ctx.request.id + ", params: " + params)
        try {
            decisionHolder.paramValidator?.apply(params) // 参数验证
        } catch (IllegalArgumentException ex) {
            log.error("参数验证失败: id: " + ctx.request.id + ", decisionId: " + decisionId + ", errMsg: " + ex.message)
            return ApiResp.fail(ex.message)
        }

        DecisionContext dCtx = new DecisionContext()
        dCtx.setDecisionHolder(decisionHolder)
        dCtx.setId(ctx.request.id)
        dCtx.setFieldManager(fieldManager)
        dCtx.setEp(ep)
        dCtx.setInput(params)
        repo.saveOrUpdate(new DecisionResult(id: dCtx.id, decisionId: decisionHolder.decision.id, occurTime: dCtx.startup))

        boolean isAsync = Boolean.valueOf(params.getOrDefault('async', false).toString())
        if (isAsync) async { dCtx.start() }
        else dCtx.start()
        return ApiResp.ok(dCtx.result())
    }


    /**
     * 查询决策结果
     */
    @Path(path = 'findDecisionResult')
    ApiResp findDecisionResult(String id) {
        if (!id) return ApiResp.fail("id must not be empty")
        def dr = repo.findById(DecisionResult, id)
        if (!dr) return ApiResp.fail("未找到记录: " + id)
        ApiResp.ok(

        )
    }


    /**
     * 加载属性配置
     */
    @Path(path = 'loadAttrCfg')
    ApiResp loadAttrCfg() {
        async {
            bean(AttrManager).loadField()
            bean(AttrManager).loadDataCollector()
            ep.fire("wsMsg_rule", '加载完成')
        }
        ApiResp.ok('加载中...')
    }
}
