package ctrl

import core.module.jpa.BaseRepo
import core.module.jpa.Page
import ctrl.common.FileData
import dao.entity.UploadFile
import io.netty.handler.codec.http.HttpResponseStatus
import ratpack.form.Form
import ratpack.handling.Chain
import ratpack.handling.RequestId
import sevice.FileUploader
import sevice.TestService

import static ctrl.common.ApiResp.ok

class TestCtrl extends CtrlTpl {

    TestCtrl() { prefix = 'test' }


    // 预处理
    def all(Chain chain) {
        chain.all{ctx ->
            println "pre process start with $prefix request"
            ctx.next()
        }
    }


    // 测试抛出错误
    def error(Chain chain) {
        chain.get('error') {ctx -> throw new RuntimeException('错误测试') }
    }


    @Lazy repo = bean(BaseRepo)

    // dao 测试
    def dao(Chain chain) {
        def srv = bean(TestService)
        chain.get('dao') {ctx ->
            if ('file' == ctx.request.queryParams.type) {
                ctx.render ok(Page.of(
                    repo.findPage(UploadFile, 0, 10, { root, query, cb -> query.orderBy(cb.desc(root.get('id')))}),
                    {UploadFile e -> [originName: e.originName, finalName: e.finalName, id: e.id] }
                ))
            } else {
                ctx.render ok(srv?.findTestData())
            }
        }
    }


    // session 测试
    def session(Chain chain) {
        chain.get('session') {ctx ->
            ctx?.sData.lastReqId = ctx.get(RequestId.TYPE).toString()
            ctx.render ok([id:ctx?.sData.id])
        }
    }


    // 接收form 表单提交
    def form(Chain chain) {
        chain.post('form', {ctx ->
            ctx.parse(Form.class).then({ form ->
                // form.file('').fileName // 提取上传的文件
                ctx.render ok(form.values())
            })
        })
    }


    // 文件上传
    def upload(Chain chain) {
        def fu = bean(FileUploader)
        def testSrv = bean(TestService)
        chain.post('upload', {ctx ->
            if (!ctx.request.contentType.type.contains('multipart/form-data')) {
                ctx.clientError(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE.code())
                return
            }
            ctx.parse(Form.class).then({form ->
                def ls = testSrv.saveUpload(
                    fu.save(form.files().values().collect{f -> new FileData(originName: f.fileName, inputStream: f.inputStream)})
                )
                // 返回上的文件的访问地址
                ctx.render ok(ls.collect{fu.toFullUrl(it.finalName)})
            })
        })
    }


    // json 参数
    def json(Chain chain) {
        chain.post('json') {ctx ->
            if (!ctx.request.contentType.type.contains('application/json')) {
                ctx.clientError(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE.code())
                return
            }
            ctx.parse(Map.class).then({ m ->
                ctx.render ok(m)
            })
        }
    }


    // 依次从外往里执行多个handler, 例: pre/sub
    def pre(Chain chain) {
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


    // 测试登录
    def testLogin(Chain chain) {
        chain.get('testLogin') {ctx ->
            ctx.sData.uRoles = ctx.request.queryParams.role
            log.warn('用户角色被改变')
            ctx.render(ok(ctx.sData))
        }
    }


    // 权限测试
    def auth(Chain chain) {
        chain.get('auth') {ctx ->
            ctx.auth('role1')
            ctx.render(ok(ctx.sData))
        }
    }
}
