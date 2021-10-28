package ctrl

import cn.xnatural.app.ServerTpl
import cn.xnatural.http.ApiResp
import cn.xnatural.http.Ctrl
import cn.xnatural.http.HttpContext
import cn.xnatural.http.Path
import cn.xnatural.jpa.Repo
import entity.DataCollector
import service.rule.CollectorManager
import service.rule.DecisionManager

import javax.inject.Inject
import javax.inject.Named

@Ctrl(prefix = 'mnt/collector')
class MntCollectorCtrl extends ServerTpl {

    @Named('jpa_rule_repo') protected Repo repo
    @Inject protected DecisionManager decisionManager
    @Inject protected CollectorManager collectorManager



    @Path(path = 'page')
    ApiResp page(HttpContext hCtx, Integer page, Integer pageSize, String kw, String id, String type) {
        if (pageSize && pageSize > 50) return ApiResp.fail("Param pageSize <=50")
        hCtx.auth("dataCollector-read")
        ApiResp.ok(
            repo.findPage(DataCollector, page, pageSize?:10) { root, query, cb ->
                query.orderBy(cb.desc(root.get('updateTime')))
                def ps = []
                if (kw) {
                    ps << cb.or(
                        cb.like(root.get('name'), '%' + kw + '%'),
                        cb.like(root.get('comment'), '%' + kw + '%')
                    )
                }
                if (id) ps << cb.equal(root.get("id"), id)
                if (type) ps << cb.equal(root.get('type'), type)
                ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
            }
        )
    }


    @Path(path = '/', method = 'put')
    ApiResp add(
        HttpContext hCtx, String name, String type, String url, String bodyStr, Boolean recordResult,
        String method, String parseScript, String contentType, String comment, String computeScript, String dataSuccessScript,
        String sqlScript, Integer minIdle, Integer maxActive, Integer timeout, Boolean enabled, String cacheKey, String cacheTimeoutFn
    ) {
        hCtx.auth('dataCollector-add')
        DataCollector collector = new DataCollector(name: name, type: type, comment: comment, enabled: (enabled == null ? true : enabled), recordResult: recordResult == null ? true : recordResult)
        if (!collector.name) return ApiResp.fail('Param name required')
        if (!collector.type) return ApiResp.fail('Param type required')
        if ('http' == collector.type) {
            if (!url) return ApiResp.fail('Param url required')
            if (!method) return ApiResp.fail('Param method required')
            if (!contentType && !'get'.equalsIgnoreCase(method)) return ApiResp.fail('Param contentType required')
            if (!url.startsWith("http") && !url.startsWith('${')) return ApiResp.fail('Param url incorrect')
            collector.parseScript = parseScript?.trim()
            if (collector.parseScript && !collector.parseScript.startsWith("{") && !collector.parseScript.endsWith("}")) {
                return ApiResp.fail('Param parseScript a function, must startWith {, endWith }')
            }
            collector.dataSuccessScript = dataSuccessScript?.trim()
            if (collector.dataSuccessScript && !collector.dataSuccessScript.startsWith("{") && !collector.dataSuccessScript.endsWith("}")) {
                return ApiResp.fail('Param dataSuccessScript a function, must startWith {, endWith }')
            }
            collector.url = url
            collector.method = method
            collector.bodyStr = bodyStr
            collector.contentType = contentType
            collector.timeout = timeout
        } else if ('script' == collector.type) {
            if (!computeScript) return ApiResp.fail('Param computeScript required')
            collector.computeScript = computeScript
        } else if ('sql' == collector.type) {
            if (!url) return ApiResp.fail('Param url required')
            if (!sqlScript) return ApiResp.fail('Param sqlScript required')
            if (!url.startsWith("jdbc")) return ApiResp.fail('url incorrect')
            if (minIdle < 0 || collector.minIdle > 20) return ApiResp.fail('Param minIdle >=0 and <= 20')
            if (maxActive < 1 || collector.maxActive > 50) ApiResp.fail('Param maxActive >=1 and <= 50')
            collector.url = url
            collector.sqlScript = sqlScript
            collector.minIdle = minIdle
            collector.maxActive = maxActive
        } else return ApiResp.fail('Not support type: ' + collector.type)
        collector.creator = hCtx.getSessionAttr("uName")
        collector.cacheKey = cacheKey
        collector.cacheTimeoutFn = cacheTimeoutFn
        try {
            repo.saveOrUpdate(collector)
        } catch (ex) {
            def cause = ex
            while (cause != null) {
                if (cause.message.contains("Duplicate entry")) {
                    return ApiResp.fail("$name aleady exist")
                }
                cause = cause.cause
            }
            throw ex
        }
        ep.fire('dataCollectorChange', collector.id)
        ep.fire('enHistory', collector, hCtx.getSessionAttr('uName'))
        ApiResp.ok(collector)
    }


    @Path(path = ':id', method = 'post')
    ApiResp update(
        HttpContext hCtx, String id, String name, String url, String bodyStr, Boolean recordResult,
        String method, String parseScript, String contentType, String comment, String computeScript, String dataSuccessScript,
        String sqlScript, Integer minIdle, Integer maxActive, Integer timeout, Boolean enabled, String cacheKey, String cacheTimeoutFn
    ) {
        hCtx.auth('dataCollector-update')
        if (!id) return ApiResp.fail("Param id required")
        if (!name) return ApiResp.fail("Param name required")
        def collector = repo.findById(DataCollector, id)
        if (collector == null) return ApiResp.fail("Param id: $id not found")
        if ('http' == collector.type) {
            if (!url) return ApiResp.fail('Param url required')
            if (!method) return ApiResp.fail('Param method required')
            if ('post'.equalsIgnoreCase(method) && !contentType) {
                return ApiResp.fail('Param contentType required')
            }
            if (!url.startsWith("http") && !url.startsWith('${')) return ApiResp.fail('Param url incorrect')
            collector.parseScript = parseScript?.trim()
            if (collector.parseScript && (!collector.parseScript.startsWith('{') || !collector.parseScript.endsWith('}'))) {
                return ApiResp.fail('Param parseScript is function, must startWith {, endWith }')
            }
            collector.dataSuccessScript = dataSuccessScript?.trim()
            if (collector.dataSuccessScript && !collector.dataSuccessScript.startsWith("{") && !collector.dataSuccessScript.endsWith("}")) {
                return ApiResp.fail('Param dataSuccessScript is function, must startWith {, endWith }')
            }
            collector.url = url
            collector.method = method
            collector.contentType = contentType
            collector.bodyStr = bodyStr
            collector.timeout = timeout
        } else if ('script' == collector.type) {
            if (!computeScript) return ApiResp.fail('Param computeScript required')
            collector.computeScript = computeScript?.trim()
            if (collector.computeScript && (collector.computeScript.startsWith('{') || collector.computeScript.endsWith('}'))) {
                return ApiResp.fail('Param computeScript is pure script. cannot startWith { or endWith }')
            }
        } else if ('sql' == collector.type) {
            if (!url) return ApiResp.fail('Param url required')
            if (!sqlScript) return ApiResp.fail('Param sqlScript required')
            if (!url.startsWith("jdbc")) return ApiResp.fail('Param url incorrect')
            if (minIdle < 0 || minIdle > 20) return ApiResp.fail('Param minIdle >=0 and <= 20')
            if (maxActive < 1 || maxActive > 50) return ApiResp.fail('Param maxActive >=1 and <= 50')
            collector.url = url
            collector.minIdle = minIdle
            collector.maxActive = maxActive
            collector.sqlScript = sqlScript
        }
        collector.name = name
        collector.comment = comment
        collector.enabled = enabled == null ? true : enabled
        collector.recordResult = recordResult == null ? true : recordResult
        collector.cacheKey = cacheKey
        collector.cacheTimeoutFn = cacheTimeoutFn
        collector.updater = hCtx.getSessionAttr("uName")

        try {
            repo.saveOrUpdate(collector)
        } catch (ex) {
            def cause = ex
            while (cause != null) {
                if (cause.message.contains("Duplicate entry")) {
                    return ApiResp.fail("$name aleady exist")
                }
                cause = cause.cause
            }
            throw ex
        }
        ep.fire('dataCollectorChange', collector.id)
        ep.fire('enHistory', collector, hCtx.getSessionAttr('uName'))
        ApiResp.ok(collector)
    }


    @Path(path = 'enable/:id/:enabled', method = 'post')
    ApiResp enable(HttpContext hCtx, String id, Boolean enabled) {
        hCtx.auth('dataCollector-update')
        if (!id) return ApiResp.fail("Param id required")
        if (enabled == null) return ApiResp.fail("Param enabled required")
        def collector = repo.findById(DataCollector, id)
        if (collector == null) return ApiResp.fail("Param id: $id not found")
        collector.enabled = enabled
        repo.saveOrUpdate(collector)
        ep.fire('dataCollectorChange', collector.id)
        ep.fire('enHistory', collector, hCtx.getSessionAttr('uName'))
        ApiResp.ok(collector)
    }


    @Path(path = ':id', method = 'delete')
    ApiResp del(HttpContext hCtx, String id) {
        if (!id) return ApiResp.fail("Param id required")
        hCtx.auth('dataCollector-del')
        def collector = repo.findById(DataCollector, id)
        if (collector == null) return ApiResp.fail("Param id: $id not found")
        repo.delete(collector)
        ep.fire('dataCollectorChange', id)
        ep.fire('enHistory', collector, hCtx.getSessionAttr('uName'))
        ApiResp.ok()
    }


    @Path(path = 'test/:id')
    ApiResp testCollector(String id, HttpContext hCtx) {
        if (!id) return ApiResp.fail("Param id required")
        ApiResp.ok(collectorManager.testCollector(id, hCtx.params()))
    }
}
