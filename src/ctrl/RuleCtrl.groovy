package ctrl

import cn.xnatural.app.ServerTpl
import cn.xnatural.http.ApiResp
import cn.xnatural.http.Ctrl
import cn.xnatural.http.HttpContext
import cn.xnatural.http.Path
import cn.xnatural.jpa.Repo
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import entity.DecideRecord
import service.rule.DecisionContext
import service.rule.DecisionManager
import service.rule.FieldManager

@Ctrl
class RuleCtrl extends ServerTpl {

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

        DecisionContext dCtx = new DecisionContext(ctx.request.id, decisionHolder, params, fieldManager, ep)

        // 初始化status: 0001, 结束status: 0000, 错误status: EEEE
        repo.saveOrUpdate(new DecideRecord(
            id: dCtx.id, decisionId: decisionHolder.decision.id, occurTime: dCtx.startup, status: '0001',
            input: JSON.toJSONString(dCtx.input, SerializerFeature.WriteMapNullValue)
        ))

        boolean isAsync = Boolean.valueOf(params.getOrDefault('async', false).toString())
        if (isAsync) async { dCtx.start() }
        else dCtx.start()
        return ApiResp.ok(dCtx.result())
    }


    /**
     * 查询决策结果
     */
    @Path(path = 'findDecideResult')
    ApiResp findDecisionResult(String decideId) {
        if (!decideId) return ApiResp.fail("decideId must not be empty")
        def dr = repo.findById(DecideRecord, decideId)
        if (!dr) return ApiResp.fail("未找到记录: " + decideId)
        def dm = bean(DecisionManager)
        def decisionHolder = dm.decisionMap.find {it.value.decision.id == dr.decisionId}.value
        def attrs = dr.data ? JSON.parseObject(dr.data) : [:]
        ApiResp.ok(
            [
                decideId: decideId, decision: dr.decisionId, decisionId: decisionHolder.decision.decisionId,
                status  : dr.status,
                desc    : dr.exception,
                attrs   : decisionHolder.spec.returnAttrs.collectEntries { name ->
                    def field = fieldManager.fieldHolders.get(name)?.field
                    def v = attrs.containsKey(name) ? attrs.get(name) : attrs.get(fieldManager.alias(name))
                    if (v instanceof Optional) {v = v.orElse( null)}
                    //如果key是中文, 则翻译成对应的英文名
                    if (field && field.cnName == name) return [field.enName, v]
                    else return [name, v]
                }
            ]
        )
    }
}
