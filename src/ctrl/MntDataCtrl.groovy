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
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.parser.Feature
import entity.CollectRecord
import entity.DecideRecord
import service.rule.*

import javax.inject.Inject
import javax.inject.Named
import java.text.SimpleDateFormat
import java.util.Map.Entry

@Ctrl(prefix = 'mnt/data')
class MntDataCtrl extends ServerTpl {

    @Named("jpa_rule_repo") protected Repo repo
    @Inject protected DecisionManager decisionManager
    @Inject protected FieldManager fieldManager
    @Inject protected CollectorManager collectorManager



    @Path(path = 'decisionResultPage')
    ApiResp decisionResultPage(
            HttpContext hCtx, Integer page, Integer pageSize, String id, String decisionId, DecideResult result,
            Long spend, String exception, String attrConditions, String startTime, String endTime
    ) {
        hCtx.auth("decideResult-read")
        if (pageSize && pageSize > 10) return ApiResp.fail("Param pageSize <=10")
        def ids = hCtx.getAttr("permissions", Set)
            .findAll {String p -> p.startsWith("decision-read-")}
            .findResults {String p -> p.replace("decision-read-", "")}
            .findAll {it}
        if (!ids) return ApiResp.ok()
        Date start = startTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(endTime) : null
        ApiResp.ok(
                repo.findPage(DecideRecord, page, pageSize?:10) { root, query, cb ->
                    def ps = []
                    if (start) ps << cb.greaterThanOrEqualTo(root.get('occurTime'), start)
                    if (end) ps << cb.lessThanOrEqualTo(root.get('occurTime'), end)
                    if (id) ps << cb.equal(root.get('id'), id)
                    ps << root.get('decisionId').in(ids)
                    if (decisionId) ps << cb.equal(root.get('decisionId'), decisionId)
                    if (spend) ps << cb.ge(root.get('spend'), spend)
                    if (result) ps << cb.equal(root.get('result'), result)
                    if (exception) ps << cb.like(root.get('exception'), '%' + exception + '%')
                    def orders = []
                    if (attrConditions) { // json查询 暂时只支持mysql5.7+,MariaDB 10.2.3+
                        JSON.parseArray(attrConditions).each {JSONObject jo ->
                            def fieldId = jo.getLong('fieldId')
                            if (!fieldId) return
                            def field = fieldManager.fieldHolders.find {it.value.field.id == fieldId}?.value?.field
                            if (field == null) return

                            def exp = cb.function("JSON_EXTRACT", String, root.get('data'), cb.literal('$.' + field.enName))
                            def op = jo['op']
                            if (op == "desc") { //降序
                                orders << cb.desc(exp.as(field.type.clzType))
                                return
                            } else if (op == 'asc') { //升序
                                orders << cb.asc(exp.as(field.type.clzType))
                                return
                            }
                            def value = jo['value']
                            if (value == null || value.empty) return

                            if (op == '=') {
                                ps << cb.equal(exp, value)
                            } else if (op == '>') {
                                ps << cb.gt(exp.as(field.type.clzType), Utils.to(value, field.type.clzType))
                            } else if (op == '<') {
                                ps << cb.lt(exp.as(field.type.clzType), Utils.to(value, field.type.clzType))
                            } else if (op == '>=') {
                                ps << cb.ge(exp.as(field.type.clzType), Utils.to(value, field.type.clzType))
                            } else if (op == '<=') {
                                ps << cb.le(exp.as(field.type.clzType), Utils.to(value, field.type.clzType))
                            } else if (op == 'contains') {
                                ps << cb.like(exp, '%' + value + '%')
                            } else throw new IllegalArgumentException("Param attrCondition op('$op') unknown")
                        }
                    }
                    if (orders) { // 按照data中的属性进行排序
                        query.orderBy(orders)
                    } else { // 默认时间降序
                        query.orderBy(cb.desc(root.get('occurTime')))
                    }
                    ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
                }.to{
                    Utils.toMapper(it).ignore("metaClass")
                            .addConverter('decisionId', 'decisionName', { String dId ->
                                decisionManager.decisionMap.find {it.value.decision.id == dId}?.value?.decision?.name
                            }).addConverter('data', {String jsonStr ->
                                it == null ? null : JSON.parseObject(jsonStr, Feature.OrderedField).collect { e ->
                                    [enName: e.key, cnName: fieldManager.fieldHolders.get(e.key)?.field?.cnName, value: e.value]
                                }}).addConverter('input', {jsonStr ->
                                    it == null ? null : JSON.parseObject(jsonStr)
                                }).addConverter('dataCollectResult', {String jsonStr ->
                                    it == null ? null : JSON.parseObject(jsonStr, Feature.OrderedField).collectEntries { e ->
                                        // 格式为: collectorId[_数据key], 把collectorId替换为收集器名字
                                        String collectorId
                                        def arr = e.key.split("_")
                                        if (arr.length == 1) collectorId = e.key
                                        else collectorId = arr[0]
                                        [
                                                (collectorManager.collectorHolders.findResult {
                                                    it.value.collector.id == collectorId ? it.value.collector.name + (arr.length > 1 ? '_' + arr.drop(1).join('_') : '') : null
                                                }?:e.key): e.value
                                        ]
                                    }
                                }).addConverter('detail', {String detailJsonStr ->
                                    if (!detailJsonStr) return null
                                    def detailJo = JSON.parseObject(detailJsonStr, Feature.OrderedField)
                                    // 数据转换
                                    detailJo.put('data', detailJo.getJSONObject('data')?.collect { Entry<String, Object> e ->
                                        [enName: e.key, cnName: fieldManager.fieldHolders.get(e.key)?.field?.cnName, value: e.value]
                                    }?:null)
                                    detailJo.getJSONArray('policies')?.each {JSONObject pJo ->
                                        pJo.put('data', pJo.getJSONObject('data')?.collect { Entry<String, Object> e ->
                                            [enName: e.key, cnName: fieldManager.fieldHolders.get(e.key)?.field?.cnName, value: e.value]
                                        }?:null)
                                        pJo.getJSONArray('items')?.each {JSONObject rJo ->
                                            rJo.put('data', rJo.getJSONObject('data')?.collect { Entry<String, Object> e ->
                                                [enName: e.key, cnName: fieldManager.fieldHolders.get(e.key)?.field?.cnName, value: e.value]
                                            }?:null)
                                        }
                                    }
                                    detailJo
                                }).build()
                }
        )
    }


    @Path(path = 'collectResultPage')
    ApiResp collectResultPage(
        HttpContext hCtx, Integer page, Integer pageSize, String decideId, String collectorType, String collector, String decisionId,
        Long spend, Boolean success, Boolean dataSuccess, Boolean cache, String startTime, String endTime
    ) {
        hCtx.auth("collectResult-read")
        def ids = hCtx.getAttr("permissions", Set)
            .findAll {String p -> p.startsWith("decision-read-")}
            .findResults {String p -> p.replace("decision-read-", "")}
            .findAll {it}
        if (!ids) return ApiResp.ok()
        Date start = startTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(endTime) : null
        if (pageSize && pageSize > 50) return ApiResp.fail("Param pageSize <=50")
        ApiResp.ok(
            repo.findPage(CollectRecord, page, pageSize?:10) { root, query, cb ->
                query.orderBy(cb.desc(root.get('collectDate')))
                def ps = []
                if (start) ps << cb.greaterThanOrEqualTo(root.get('collectDate'), start)
                if (end) ps << cb.lessThanOrEqualTo(root.get('collectDate'), end)
                if (decideId) ps << cb.equal(root.get('decideId'), decideId)
                ps << root.get('decisionId').in(ids)
                if (decisionId) ps << cb.equal(root.get('decisionId'), decisionId)
                if (collectorType) ps << cb.equal(root.get('collectorType'), collectorType)
                if (collector) ps << cb.equal(root.get('collector'), collector)
                if (spend) ps << cb.ge(root.get('spend'), spend)
                if (success != null) {
                    if (success) {
                        ps << cb.equal(root.get('status'), '0000')
                    } else {
                        ps << cb.notEqual(root.get('status'), '0000')
                    }
                }
                if (dataSuccess != null) {
                    if (dataSuccess) {
                        ps << cb.equal(root.get('dataStatus'), '0000')
                    } else {
                        ps << cb.notEqual(root.get('dataStatus'), '0000')
                    }
                }
                if (cache != null) {
                    if (cache) {
                        ps << cb.equal(root.get('cache'), true)
                    } else {
                        ps << cb.notEqual(root.get('cache'), true)
                    }
                }
                ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
            }.to{record -> Utils.toMapper(record).ignore("metaClass")
                .addConverter('decisionId', 'decisionName', {String dId ->
                    decisionManager.decisionMap.find {it.value.decision.id == dId}?.value?.decision?.name
                }).addConverter('collector', 'collectorName', {String cId ->
                    collectorManager.collectorHolders.get(cId)?.collector?.name
                }).addConverter('collector', 'collectorType', {String cId ->
                    collectorManager.collectorHolders.get(cId)?.collector?.type
                }).build()
            }
        )
    }


    /**
     * 统计一段时间的决策结果
     * @param startTime required yyyy-MM-dd HH:mm:ss
     * @param endTime yyyy-MM-dd HH:mm:ss
     */
    @Path(path = 'countDecide')
    ApiResp countDecide(HttpContext hCtx, String startTime, String endTime) {
        if (startTime == null) return ApiResp.fail("Param startTime required")
        Date start = startTime ? new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(endTime) : null
        def ids = hCtx.getAttr("permissions", Set)
                .findAll {String p -> p.startsWith("decision-read-")}
                .findResults {String p -> p.replace("decision-read-", "")}
                .findAll {it}
        if (!ids) return ApiResp.ok()
        hCtx.response.cacheControl(2) // 缓存2秒
        String sql = """
            select t1.decision_id, t2.name decisionName, t1.result, count(1) total from ${repo.tbName(DecideRecord).replace("`", '')} t1
            left join decision t2 on t1.decision_id = t2.id
            where t1.occur_time>=:start${end ? " and t1.occur_time<=:end" : ""} and t1.decision_id in (:ids) and t1.result is not null 
            group by t1.decision_id, t1.result
        """.trim()
        ApiResp.ok(end ? repo.rows(sql, start, end, ids) : repo.rows(sql, start, ids))
    }


    /**
     * 统计一段时间的规则结果
     * @param decisionId 决策id
     * @param startTime required yyyy-MM-dd HH:mm:ss
     * @param endTime yyyy-MM-dd HH:mm:ss
     */
    @Path(path = 'countRule')
    ApiResp countRule(HttpContext hCtx, String decisionId, String startTime, String endTime) {
        if (startTime == null) return ApiResp.fail("Param startTime required")
        Date start = startTime ? new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(endTime) : null
        def ids = hCtx.getAttr("permissions", Set)
                .findAll {String p -> p.startsWith("decision-read-")}
                .findResults {String p -> p.replace("decision-read-", "")}
                .findAll {it}
        if (!ids) return ApiResp.ok().desc("无可查看的决策")
        ids = decisionId ? ids.findAll {it == decisionId} : ids
        if (!ids) return ApiResp.ok().desc("无可查看的决策")
        hCtx.response.cacheControl(60) // 缓存60秒

        // mysql 8.0.4+ or mariaDb 10.6+ 有json_table函数
        def verArr = repo.getDBVersion().split("\\.")
        if (
            (repo.dialect.containsIgnoreCase("mysql") && verArr[0].toInteger() >= 8 && verArr[2].toInteger() >= 4) ||
            (repo.dialect.containsIgnoreCase("maria") && verArr[0].toInteger() >= 10 && verArr[1].toInteger() >= 6)
        ) {
            String sql = """
                SELECT
                    t1.decision_id decisionId, t3.name decisionName, t2.policyName, t2.ruleName, t2.result, count(1) total
                FROM decide_record t1
                join json_table(JSON_SET(t1.detail, '\$.id', t1.id),
                    '\$' COLUMNS (
                        id varchar(50) path '\$.id',
                        NESTED PATH '\$.policies[*]' COLUMNS (
                            policyName varchar(200) path '\$.attrs."策略名"',
                                NESTED PATH '\$.rules[*]' COLUMNS (
                                    ruleName varchar(200) path '\$.attrs."规则名"',
                                    result varchar(20) path '\$.result'
                        )))
                ) t2 on t2.id=t1.id
                left join decision t3 on t1.decision_id = t3.id
                where
                    t1.occur_time>=:start${end ? " and t1.occur_time<=:end" : ""} and t1.decision_id in (:ids)
                    and t1.detail is not null and t1.result is not null
                group by decisionId, policyName, ruleName, result
                order by case t2.result when 'Reject' then 3 when 'Review' then 2 when 'Accept' then 1 when isnull(t2.result) then 0 end desc, total desc
                limit 30
            """
            return ApiResp.ok(end ? repo.rows(sql, start, end, ids) : repo.rows(sql, start, ids))
        }

        String sql = """
            select t1.decision_id decisionId, t2.name decisionName, t1.detail 
            from ${repo.tbName(DecideRecord).replace("`", '')} t1
            left join decision t2 on t1.decision_id = t2.id
            where 
                t1.occur_time>=:start${end ? " and t1.occur_time<=:end" : ""} and t1.decision_id in (:ids)
                and t1.detail is not null and t1.result is not null
        """.trim()

        def ls = [] as LinkedList
        for (int page = 1, pageSize = 100; ;page++) {
            Page rPage = end ? repo.sqlPage(sql, page, pageSize, start, end, ids) : repo.sqlPage(sql, page, pageSize, start, ids)
            ls.addAll(rPage.list)
            if (page >= rPage.totalPage) break
        }
        // decisionId, decisionName, policyName, ruleName, result, total
        ApiResp.ok(
                ls.findResults {Map<String, String> record ->
                    record["detail"] ? JSON.parseObject(record["detail"])?.getJSONArray("policies")?.findResults {JSONObject pJo ->
                        pJo.getJSONArray("items")?.findAll {JSONObject rJo -> rJo['attrs']['规则名']}?.findResults { JSONObject rJo ->
                            record['decisionId'] + '||' + record['decisionName'] + '||' + pJo['attrs']['策略名'] +'||' + rJo['attrs']['规则名'] + '||' + (rJo['result']?:DecideResult.Accept)
                        }?:[]
                    }?.flatten() : []
                }.flatten().countBy {it}.findResults {e ->
                    def arr = e.key.split("\\|\\|")
                    return [decisionId: arr[0], decisionName: arr[1], policyName: arr[2], ruleName: arr[3], result: arr[4], total: e.value]
                }.sort {o1, o2 ->
                    // 把拒绝多的排前面
                    if (o1['result'] == "Reject" && o2['result'] == "Reject") return o2['total'] - o1['total']
                    else if (o1['result'] == "Reject") return -1
                    else if (o2['result'] == "Reject") return 1
                    else return 0
                }.takeRight(decisionId ? Integer.MAX_VALUE : 30) // 如果是指定某个决策, 则全部显示, 如果是查所有则限制显示(有可能会得多)
        )
    }


    @Path(path = 'cleanExpire')
    ApiResp cleanExpire(HttpContext hCtx) {
        hCtx.auth("grant")
        bean(DecisionSrv).cleanDecideRecord()
        return ApiResp.ok().desc("等待后台清理完成...")
    }
}
