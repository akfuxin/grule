package ctrl

import cn.xnatural.app.ServerTpl
import cn.xnatural.app.Utils
import cn.xnatural.http.ApiResp
import cn.xnatural.http.Ctrl
import cn.xnatural.http.HttpContext
import cn.xnatural.http.Path
import cn.xnatural.jpa.Repo
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import entity.DataCollector
import entity.FieldType
import entity.RuleField
import service.rule.CollectorManager
import service.rule.DecisionManager
import service.rule.FieldManager

import javax.inject.Inject
import javax.inject.Named

@Ctrl(prefix = 'mnt/field')
class MntFieldCtrl extends ServerTpl {

    @Named("jpa_rule_repo") protected Repo repo
    @Inject protected DecisionManager decisionManager
    @Inject protected FieldManager fieldManager
    @Inject protected CollectorManager collectorManager
    // 指标字符限制: 字母,中文开头,可包含字数,字母,下划线,中文
    @Lazy protected fieldNamePattern = /^[a-zA-Z\u4E00-\u9FA5]+[0-9a-zA-Z\u4E00-\u9FA5_]*$/


    @Path(path = 'page')
    ApiResp page(HttpContext hCtx, Integer page, Integer pageSize, String collector, String decision, String kw) {
        if (pageSize && pageSize > 50) return ApiResp.fail("Param pageSize <=50")
        hCtx.auth("field-read")
        ApiResp.ok(repo.findPage(RuleField, page, (pageSize?:10)) { root, query, cb ->
            query.orderBy(cb.desc(root.get('updateTime')))
            def ps = []
            if (kw) {
                ps << cb.or(
                        cb.like(root.get('enName'), '%' + kw + '%'),
                        cb.like(root.get('cnName'), '%' + kw + '%'),
                        cb.like(root.get('comment'), '%' + kw + '%')
                )
            }
            if (collector) {
                ps << cb.function("JSON_SEARCH", String, root.get('collectorOptions'), cb.literal('one'), cb.literal(collector)).isNotNull()
            }
            if (decision) {
                ps << cb.equal(root.get("decision"), decision)
            }
            ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
        }.to{ Utils.toMapper(it).ignore("metaClass")
            .addConverter("decision", "decisionName") {dId -> dId ? decisionManager.decisionMap.find {it.value.decision.id == dId}?.value?.decision?.name : null}
            .addConverter("decision", "decisionId") {dId -> dId ? decisionManager.decisionMap.find {it.value.decision.id == dId}?.value?.decision?.decisionId : null}
            .addConverter("collectorOptions") {String opts ->
                JSON.parseArray(opts).collect {JSONObject jo ->
                    jo.fluentPut(
                            "collectorName",
                            collectorManager.collectorHolders.find {it.key == jo['collectorId']}?.value?.collector?.name
                    )
                }
            }
            .build()
        })
    }


    @Path(path = '/', method = 'put')
    ApiResp add(HttpContext hCtx, String enName, String cnName, FieldType type, String comment, String collectorOptions) {
        hCtx.auth('field-add')
        if (!enName) return ApiResp.fail("Param enName required")
        if (!cnName) return ApiResp.fail("Param cnName required")
        if (!type) return ApiResp.fail("Param type required")
        if (!(enName ==~ fieldNamePattern)) {
            return ApiResp.fail("Param enName illegal: 字母或中文开头,可包含字数,字母,下划线,中文")
        }
        if (!(cnName ==~ fieldNamePattern)) {
            return ApiResp.fail("Param enName illegal: 字母或中文开头,可包含字数,字母,下划线,中文")
        }
        // 验证 collectorOptions
        if (collectorOptions) {
            JSON.parseArray(collectorOptions).each {JSONObject jo ->
                if (!jo['collectorId']) throw new IllegalArgumentException("Param collectorOptions.collectorId required")
                if (!repo.count(DataCollector) {root, query, cb -> cb.equal(root.get("id"), jo['collectorId'])}) {
                    throw new IllegalArgumentException("Param collectorOptions.collectorId not found")
                }
            }
        }
        def field = new RuleField(
            enName: enName, cnName: cnName, type: type, comment: comment,
            collectorOptions: collectorOptions, creator: hCtx.getSessionAttr('uName')
        )
        try {
            repo.saveOrUpdate(field)
        } catch(ex) {
            def cause = ex
            while (cause != null) {
                if (cause.message.contains("Duplicate entry")) {
                    if (cause.message.contains("cnName")) {
                        return ApiResp.fail("$cnName aleady exist")
                    }
                    if (cause.message.contains("enName")) {
                        return ApiResp.fail("$enName aleady exist")
                    }
                }
                cause = cause.cause
            }
            throw ex
        }
        ep.fire('fieldChange', field.id)
        ep.fire('enHistory', field, hCtx.getSessionAttr('uName'))
        ApiResp.ok(field)
    }


    @Path(path = ':id', method = 'post')
    ApiResp update(HttpContext hCtx, Long id, String enName, String cnName, FieldType type, String comment, String collectorOptions) {
        hCtx.auth('field-update')
        if (!id) return ApiResp.fail("Param id required")
        if (!enName) return ApiResp.fail("Param enName required")
        if (!cnName) return ApiResp.fail("Param cnName required")
        if (!type) return ApiResp.fail("Param type required")
        if (!(enName ==~ fieldNamePattern)) {
            return ApiResp.fail("Param enName illegal: 字母或中文开头,可包含字数,字母,下划线,中文")
        }
        if (!(cnName ==~ fieldNamePattern)) {
            return ApiResp.fail("Param enName illegal: 字母或中文开头,可包含字数,字母,下划线,中文")
        }
        def field = repo.findById(RuleField, id)
        if (field == null) return ApiResp.fail("Param id: $id not found")

        // 验证 collectorOptions
        if (collectorOptions) {
            JSON.parseArray(collectorOptions).each {JSONObject jo ->
                if (!jo['collectorId']) throw new IllegalArgumentException("Param collectorOptions.collectorId required")
                if (!repo.count(DataCollector) {root, query, cb -> cb.equal(root.get("id"), jo['collectorId'])}) {
                    throw new IllegalArgumentException("Param collectorOptions.collectorId not found")
                }
            }
        }
        field.enName = enName
        field.cnName = cnName
        field.type = type
        field.comment = comment
        field.collectorOptions = collectorOptions
        field.updater = hCtx.getSessionAttr("uName")
        try {
            repo.saveOrUpdate(field)
        } catch (ex) {
            def cause = ex
            while (cause != null) {
                if (cause.message.contains("Duplicate entry")) {
                    if (cause.message.contains("cnName")) {
                        return ApiResp.fail("$cnName aleady exist")
                    }
                    if (cause.message.contains("enName")) {
                        return ApiResp.fail("$enName aleady exist")
                    }
                }
                cause = cause.cause
            }
            throw ex
        }
        ep.fire('fieldChange', field.id)
        ep.fire('enHistory', field, hCtx.getSessionAttr('uName'))
        ApiResp.ok(field)
    }


    @Path(path = ':id', method = "delete")
    ApiResp del(HttpContext hCtx, Long id) {
        if (!id) return ApiResp.fail("Param id required")
        hCtx.auth('field-del')
        def field = repo.findById(RuleField, id)
        if (!field) return ApiResp.fail("Param id not found")
        repo.delete(field)
        ep.fire('fieldChange', id)
        ep.fire('enHistory', field, hCtx.getSessionAttr('uName'))
        ApiResp.ok()
    }
}
