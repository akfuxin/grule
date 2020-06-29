package ctrl

import cn.xnatural.enet.event.EL
import com.alibaba.fastjson.JSON
import core.Page
import core.Utils
import core.module.OkHttpSrv
import core.module.SchedSrv
import core.module.jpa.BaseRepo
import ctrl.common.FileData
import dao.entity.Test
import dao.entity.UploadFile
import dao.entity.VersionFile
import org.hibernate.query.internal.NativeQueryImpl
import org.hibernate.transform.Transformers
import ratpack.exec.Promise
import ratpack.handling.Chain
import ratpack.handling.RequestId
import ratpack.websocket.*
import service.FileUploader
import service.TestService

import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

import static ctrl.common.ApiResp.fail
import static ctrl.common.ApiResp.ok

class TestCtrl extends CtrlTpl {

    @Lazy def srv = bean(TestService)
    @Lazy def repo = bean(BaseRepo)
    @Lazy def fu = bean(FileUploader)
    final Set<WebSocket> wss = ConcurrentHashMap.newKeySet()

    TestCtrl() { prefix = 'test' }


    @EL(name = 'testWsMsg')
    void wsMsg(String msg) {
        wss.each {ws -> ws.send(msg)}
    }


    // 预处理
    void all(Chain chain) {
        chain.all{ctx ->
            // TODO 预处理 #prefix 前缀开头的 接口
            // println "pre process start with $prefix request"
            ctx.next()
        }
    }


    // 测试抛出错误
    void error(Chain chain) {
        chain.get('error') {ctx -> throw new RuntimeException('错误测试') }
    }


    // dao 测试
    void dao(Chain chain) {
        chain.get('dao') {
            get(it) {params ->
                if ('file' == params.type) {
                    return Page.of(
                        repo.findPage(UploadFile, 0, 10, {root, query, cb -> query.orderBy(cb.desc(root.get('id')))}),
                        {UploadFile e ->
                            Utils.toMapper(e).ignore("updateTime")
                                .addConverter("createTime", {Date d -> d?.getTime()}).build()
                        }
                    )
                } else {
                    return srv?.findTestData()
                }
                return

                // 转换成map
                repo.trans{s ->
                    s.createNativeQuery("SELECT * from test")
                        .unwrap(NativeQueryImpl).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP).list()
                }.collect {
                    Utils.toMapper(it).ignore("update_time")
                        .addConverter("create_time", {Date d -> d?.getTime()}).build()
                }

                // 非数字比较大小(时间大小比较)
                repo.find(Test, {root, query, cb -> cb.greaterThanOrEqualTo(root.get("createTime"), new Date())})
            }
        }
    }


    // session 测试
    void session(Chain chain) {
        chain.get('session') {ctx ->
            ctx?.sData.lastReqId = ctx.get(RequestId.TYPE).toString()
            ctx.render ok([id:ctx?.sData.id])
        }
    }


    // websocket
    void ws(Chain chain) {
        chain.get('ws') {ctx ->
            WebSockets.websocket(ctx, new WebSocketHandler<WebSocket>() {
                @Override
                WebSocket onOpen(WebSocket ws) throws Exception {
                    log.info('ws connect. {}', ctx.request.remoteAddress)
                    wss.add(ws)
                    return ws
                }

                @Override
                void onClose(WebSocketClose<WebSocket> close) throws Exception {
                    // ratpack.websocket.internal.DefaultWebSocket.close(int, java.lang.String)
                    wss.remove(close.getOpenResult())
                    log.info('ws closed. {}' + close.fromClient + ', fromServer: ' + close.fromServer, ctx.request.remoteAddress)
                }

                @Override
                void onMessage(WebSocketMessage<WebSocket> frame) throws Exception {
                    log.info('test ws receive client msg: {}', frame.text)
                }
            })
        }
        bean(SchedSrv)?.cron('0 0/1 * * * ?') {
            wsMsg(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
        }
    }


    // 接收form 表单提交
    void form(Chain chain) {
        chain.post('form', {
            form(it) {fd -> fd.toMapString()}
        })
    }


    // 文件上传
    void upload(Chain chain) {
        chain.post('upload', {ctx ->
            form(ctx) {form ->
                def f = form.file("file")
                if (f == null) {throw new IllegalArgumentException('文件未上传')}
                // 返回上的文件的访问地址
                repo.trans {
                    fu.save([new FileData(originName: f.fileName, inputStream: f.inputStream)])
                        .collect {new VersionFile(version: form.get("version"), finalName: it.finalName, originName: it.originName, size: it.size)}
                        .each { repo.saveOrUpdate(it) }
                }.collect{fu.toFullUrl(it.finalName)}
            }
        })
    }


    // json 参数
    void json(Chain chain) {
        chain.post('json') {ctx ->
            json(ctx) {jsonStr -> JSON.parseObject(jsonStr) }
        }
    }


    // 接收post string
    void string(Chain chain) {
        chain.post("str") {
            string(it) {s -> return s}
        }
    }


    // 依次从外往里执行多个handler, 例: pre/sub
    void pre(Chain chain) {
        chain.prefix('pre') {ch ->
            ch.with {
                all({ctx ->
                    println('xxxxxxxxxxxxxxxx')
                    ctx.next()
                })
                get('sub', { ctx -> ctx.render 'pre/sub' })
                get('sub2', { ctx -> ctx.render 'pre/sub2' })
            }
        }
    }


    // 下载excel文件
    void downXlsx(Chain chain) {
        chain.post('downXlsx') {ctx ->
            ctx.response.contentType('application/vnd.ms-excel;charset=utf-8')
            ctx.header('Content-Disposition', "attachment;filename=${new SimpleDateFormat("yyyyMMddHHmmss").format(new Date())}.xlsx")
            def wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(true)
            def bos = new ByteArrayOutputStream(); wb.write(bos)
            ctx.response.send(bos.toByteArray())
            // wb.write(ctx.response)
        }
    }


    // 异步处理
    void async(Chain chain) {
        chain.get('async') {ctx ->
            ctx.render Promise.async{down ->
                async {
                    Thread.sleep(3000)
                    down.success(ok('date', new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())))
                }
            }
        }
    }


    // 测试登录
    void testLogin(Chain chain) {
        chain.get('testLogin') {ctx ->
            ctx['sData']['uRoles'] = Arrays.stream(ctx.request.queryParams.role.split(',')).map{it.trim()}.filter{it?true:false}.collect(Collectors.toSet())
            log.warn('用户权限角色被改变. {}', ctx['sData']['uRoles'])
            ctx.render ok(ctx['sData'])
        }
    }


    // 权限测试
    void auth(Chain chain) {
        chain.get('auth') {ctx ->
            ctx.auth(ctx.request.queryParams['role'])
            ctx.render ok(ctx['sData'])
        }
    }


    // 远程事件调用
    void remote(Chain chain) {
        def ts = bean(TestService)
        chain.path('remote') {ctx ->
            ctx.render Promise.async { down ->
                async {ts.remote(ctx.request.queryParams['app']?:"gy", ctx.request.queryParams['event']?:'eName1', ctx.request.queryParams['param']?:'p1',{
                    if (it instanceof Exception) down.success(fail(it.message))
                    else down.success ok(it)
                })}
            }
        }
    }


    void http(Chain chain) {
        def http = bean(OkHttpSrv)
        chain.path("http") {ctx ->
            ctx.render Promise.async {down ->
                ctx.render http.get(ctx.request.queryParams['url']?:'http://gy/test/cus').debug().execute()
            }
        }
    }


    // 测试自定义返回
    void cus(Chain chain) {
        chain.path('cus') {ctx ->
            def t = bean(BaseRepo).saveOrUpdate(new Test(name: "xxx" + System.currentTimeMillis()))
            ctx.render(t.id)
            // ctx.render new JSONObject().fluentPut("code", "0000").toString()
        }
    }

    /**@
     * 超时接口
     * @param chain
     */
    void timeout(Chain chain) {
        chain.path('timeout') {ctx ->
            ctx.render Promise.async{down ->
                async {
                    Thread.sleep(Integer.valueOf(ctx.request.queryParams['timeout']?:10) * 1000L)
                    down.success(ok())
                }
            }
        }
    }
}
