package ctrl

import ctrl.common.ApiResp
import ratpack.exec.Promise
import ratpack.handling.Chain
import ratpack.handling.RequestId
import service.rule.AttrManager
import service.rule.DecisionEngine
import service.rule.DecisionManager
import service.rule.PolicyManger

import java.nio.charset.Charset

class RuleCtrl extends CtrlTpl {

    /**
     * 执行一条决策
     */
    void decision(Chain chain) {
        def re = bean(DecisionEngine)
        chain.path('decision') {ctx ->
            // get
            ctx.render Promise.async{ down ->
                async {
                    try {
                        String pn = ctx.request.queryParams['decisionId']
                        if (!pn) throw new IllegalArgumentException("decisionId must not be empty")
                        boolean async = ctx.request.queryParams['async'] == 'true' ? true : false
                        down.success(ApiResp.ok(
                            re.run(pn, async, ctx.get(RequestId.TYPE).toString(), ctx.request.queryParams)
                        ))
                    } catch (Exception ex) {
                        down.success(ApiResp.fail(ex.message?:ex.class.simpleName))
                        log.info("", ex)
                    }
                }
            }
        }
    }


    /**
     * 设置一条策略
     */
    void setPolicy(Chain chain) {
        chain.post('setPolicy') {ctx ->
            ctx.request.body.then {data ->
                ctx.render Promise.async{ down ->
                    async {
                        try {
                            def p = bean(PolicyManger).create(data.getText(Charset.forName('utf-8')))
                            down.success(ApiResp.ok(p))
                        } catch (Exception ex) {
                            down.success(ApiResp.fail(ex.message?:ex.class.simpleName))
                            log.info("", ex)
                        }
                    }
                }
            }
        }
    }


    /**
     * 设置一条决策
     */
    void setDecision(Chain chain) {
        chain.post('setDecision') {ctx ->
            ctx.request.body.then {data ->
                ctx.render Promise.async{ down ->
                    async {
                        try {
                            def p = bean(DecisionManager).create(data.getText(Charset.forName('utf-8')))
                            down.success(ApiResp.ok(p))
                        } catch (Exception ex) {
                            down.success(ApiResp.fail(ex.message?:ex.class.simpleName))
                            log.info("", ex)
                        }
                    }
                }
            }
        }
    }


    /**
     * 加载属性配置
     */
    void loadAttrCfg(Chain chain) {
        def am = bean(AttrManager)
        chain.path('loadAttrCfg') {ctx ->
            async {am.init()}
            ctx.render ApiResp.ok('加载中...')
        }
    }
}
