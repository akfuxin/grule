package ctrl

import cn.xnatural.app.CacheSrv
import cn.xnatural.app.ServerTpl
import cn.xnatural.app.Utils
import cn.xnatural.http.*
import cn.xnatural.jpa.Repo
import entity.User
import service.FileUploader

import java.util.function.Supplier

@Ctrl
class MainCtrl extends ServerTpl {

    @Lazy protected fileUploader = bean(FileUploader)
    @Lazy protected cacheSrv = bean(CacheSrv)
    @Lazy protected repo = bean(Repo, 'jpa_rule_repo')
    // 需要登录权限的页面
    protected final Map<String, String> auth_page = [
            "config/OpHistory.vue": "opHistory-read", "config/FieldConfig.vue": "field-read",
            "config/DataCollectorConfig.vue": "dataCollector-read", "config/Permission.vue": "grant",
            "data/DecideResult.vue": "decideResult-read", "data/CollectResult.vue": "collectResult-read"
    ]

    @Filter
    void filter(HttpContext hCtx) {
        // 需要登录权限的请求路径过虑判断
        if (
            (hCtx.pieces?[0] == 'mnt' && !(hCtx.pieces?[1] == 'login')) ||
            auth_page.keySet().find {hCtx.request.path.endsWith(it)}
        ) {
            def uId = hCtx.getSessionAttr('uId')
            if (uId) {
                // 设置用户权限绑定到请求
                hCtx.setAttr('permissions', {
                    def pIds = cacheSrv?.get("permission_" + uId)
                    if (pIds == null) {
                        def permissions = repo.findById(User, Utils.to(uId, Long)).permissions
                        pIds = permissions?.split(',')?.toList()?.toSet()?:Collections.emptySet()
                        cacheSrv?.set("permission_" + uId, pIds)
                    }
                    return pIds
                } as Supplier)
            } else {
                hCtx.response.status(401)
                hCtx.render(ApiResp.fail('用户会话已失效, 请重新登录'))
            }
        }
    }


    @Path(path = ['index.html', '/'])
    File index(HttpContext hCtx) {
        hCtx.response.cacheControl(10)
        Utils.baseDir("static/index.html")
    }

    @Path(path = 'test.html')
    File testHtml(HttpContext hCtx) {
        hCtx.response.cacheControl(3)
        Utils.baseDir("static/test.html")
    }

    @Path(path = 'favicon.ico')
    File favicon(HttpContext hCtx) {
        hCtx.response.cacheControl(3000)
        hCtx.response.contentType("image/x-icon")
        Utils.baseDir("static/favicon.ico")
    }


    @Path(path = 'health')
    ApiResp health() {
        ApiResp.ok(
            ['status': 'GREEN', 'detail':
                [
                    'db': ['status': 'UP'],
                ],
            ]
        )
    }


    @Path(path = 'file/:fName')
    File file(String fName, HttpContext hCtx) {
        if (app().profile == 'pro') {
            hCtx.response.cacheControl(1800)
        }
        fileUploader.findFile(fName)
    }


    // ====================== api-doc =========================

    @Path(path = 'api-doc/:fName.json', produce = 'application/json')
    String swagger_data(String fName, HttpContext hCtx) {
        def f = Utils.baseDir("../conf/${fName}.json")
        if (f.exists()) {
            return f.getText('utf-8')
        }
        null
    }
    @Path(path = 'api-doc/:fName')
    File swagger_ui(String fName, HttpContext hCtx) {
        hCtx.response.cacheControl(1800)
        Utils.baseDir("static/swagger-ui/${fName == '/' ? 'index.html' : fName}")
    }

    // ==========================js =====================

    @Path(path = 'js/:fName')
    File js(String fName, HttpContext hCtx) {
        if (app().profile == 'pro') {
            hCtx.response.cacheControl(1800)
        }
        Utils.baseDir("static/js/$fName")
    }
    @Path(path = 'js/lib/{fName}')
    File js_lib(String fName, HttpContext hCtx) {
        hCtx.response.cacheControl(86400) // 一天
        Utils.baseDir("static/js/lib/$fName")
    }

    @Path(path = 'components/:fName')
    File components(String fName, HttpContext hCtx) {
        if (app().profile == 'pro') hCtx.response.cacheControl(1800)
        Utils.baseDir("static/components/$fName")
    }
    @Path(path = 'views/:fName')
    File views(String fName, HttpContext hCtx) {
        if (app().profile == 'pro') hCtx.response.cacheControl(1800)
        def permission = auth_page.get(fName)
        if (permission) hCtx.auth(permission)
        Utils.baseDir("static/views/$fName")
    }


    // =======================css ========================

    @Path(path = 'css/:fName')
    File css(String fName, HttpContext hCtx) {
        if (app().profile == 'pro') hCtx.response.cacheControl(1800)
        Utils.baseDir("static/css/$fName")
    }
    @Path(path = 'css/fonts/:fName')
    File css_fonts(String fName, HttpContext hCtx) {
        hCtx.response.cacheControl(86400) // 一天
        Utils.baseDir("static/css/fonts/$fName")
    }
    @Path(path = 'css/lib/:fName')
    File css_lib(String fName, HttpContext hCtx) {
        hCtx.response.cacheControl(86400) // 一天
        Utils.baseDir("static/css/lib/$fName")
    }

    // ================= 图片 ======================
    @Path(path = 'img/:fName')
    File img(String fName, HttpContext hCtx) {
        hCtx.response.cacheControl(172800)
        Utils.baseDir("static/img/$fName")
    }
}
