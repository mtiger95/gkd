package li.songe.gkd.debug

import android.app.Service
import android.content.Intent
import com.blankj.utilcode.util.LogUtils
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import li.songe.gkd.appScope
import li.songe.gkd.data.AppInfo
import li.songe.gkd.data.DeviceInfo
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.SubsItem
import li.songe.gkd.data.selfAppInfo
import li.songe.gkd.db.DbSet
import li.songe.gkd.notif.StopServiceReceiver
import li.songe.gkd.notif.httpNotif
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.LOCAL_HTTP_SUBS_ID
import li.songe.gkd.util.OnCreate
import li.songe.gkd.util.OnDestroy
import li.songe.gkd.util.SERVER_SCRIPT_URL
import li.songe.gkd.util.deleteSubscription
import li.songe.gkd.util.getIpAddressInLocalNetwork
import li.songe.gkd.util.isPortAvailable
import li.songe.gkd.util.keepNullJson
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.map
import li.songe.gkd.util.startForegroundServiceByClass
import li.songe.gkd.util.stopServiceByClass
import li.songe.gkd.util.subsItemsFlow
import li.songe.gkd.util.toast
import li.songe.gkd.util.updateSubscription
import li.songe.gkd.util.useAliveFlow
import li.songe.gkd.util.useLogLifecycle


class HttpService : Service(), OnCreate, OnDestroy {
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        onCreated()
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroyed()
    }

    val scope = MainScope().apply { onDestroyed { cancel() } }

    private val httpServerPortFlow = storeFlow.map(scope) { s -> s.httpServerPort }

    init {
        useLogLifecycle()
        useAliveFlow(isRunning)
        onCreated { toast("HTTP服务已启动") }
        onDestroyed { toast("HTTP服务已停止") }

        onCreated { localNetworkIpsFlow.value = getIpAddressInLocalNetwork() }

        onDestroyed {
            if (storeFlow.value.autoClearMemorySubs) {
                deleteSubscription(LOCAL_HTTP_SUBS_ID)
            }
            httpServerFlow.value = null
        }
        StopServiceReceiver.autoRegister(this)
        onCreated {
            httpNotif.notifyService(this)
            scope.launchTry(Dispatchers.IO) {
                httpServerPortFlow.collect { port ->
                    httpServerFlow.value?.stop()
                    httpServerFlow.value = null
                    if (!isPortAvailable(port)) {
                        toast("端口 $port 被占用, 请更换后重试")
                        stopSelf()
                        return@collect
                    }
                    httpServerFlow.value = try {
                        scope.createServer(port).apply { start() }
                    } catch (e: Exception) {
                        toast("HTTP服务启动失败:${e.stackTraceToString()}")
                        LogUtils.d("HTTP服务启动失败", e)
                        null
                    }
                    if (httpServerFlow.value == null) {
                        stopSelf()
                        return@collect
                    }
                    httpNotif.copy(text = "HTTP服务-$port").notifyService(this@HttpService)
                }
            }
        }
    }

    companion object {
        val httpServerFlow = MutableStateFlow<ServerType?>(null)
        val isRunning = MutableStateFlow(false)
        val localNetworkIpsFlow = MutableStateFlow(emptyList<String>())
        fun stop() = stopServiceByClass(HttpService::class)
        fun start() = startForegroundServiceByClass(HttpService::class)
    }
}

typealias ServerType = EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>


@Serializable
data class RpcOk(
    val message: String? = null,
)

@Serializable
data class ReqId(
    val id: Long,
)

@Serializable
data class ServerInfo(
    val device: DeviceInfo = DeviceInfo.instance,
    val gkdAppInfo: AppInfo = selfAppInfo
)

fun clearHttpSubs() {
    // 如果 app 被直接在任务列表划掉, HTTP订阅会没有清除, 所以在后续的第一次启动时清除
    if (HttpService.isRunning.value) return
    appScope.launchTry(Dispatchers.IO) {
        delay(1000)
        if (storeFlow.value.autoClearMemorySubs) {
            deleteSubscription(LOCAL_HTTP_SUBS_ID)
        }
    }
}

private val httpSubsItem by lazy {
    SubsItem(
        id = LOCAL_HTTP_SUBS_ID,
        order = -1,
        enableUpdate = false,
    )
}

private fun CoroutineScope.createServer(port: Int) = embeddedServer(CIO, port) {
    install(KtorCorsPlugin)
    install(KtorErrorPlugin)
    install(ContentNegotiation) { json(keepNullJson) }
    routing {
        get("/") { call.respondText(ContentType.Text.Html) { "<script type='module' src='$SERVER_SCRIPT_URL'></script>" } }
        route("/api") {
            post("/getServerInfo") { call.respond(ServerInfo()) }
            post("/getSnapshot") {
                val data = call.receive<ReqId>()
                val fp = SnapshotExt.snapshotFile(data.id)
                if (!fp.exists()) {
                    throw RpcError("对应快照不存在")
                }
                call.respond(fp)
            }
            post("/getScreenshot") {
                val data = call.receive<ReqId>()
                val fp = SnapshotExt.screenshotFile(data.id)
                if (!fp.exists()) {
                    throw RpcError("对应截图不存在")
                }
                call.respondFile(fp)
            }
            post("/captureSnapshot") {
                call.respond(SnapshotExt.captureSnapshot())
            }
            post("/getSnapshots") {
                call.respond(DbSet.snapshotDao.query().first())
            }
            post("/updateSubscription") {
                val subscription =
                    RawSubscription.parse(call.receiveText(), json5 = false)
                        .copy(
                            id = LOCAL_HTTP_SUBS_ID,
                            name = "内存订阅",
                            version = 0,
                            author = "@gkd-kit/inspect"
                        )
                updateSubscription(subscription)
                DbSet.subsItemDao.insert((subsItemsFlow.value.find { s -> s.id == httpSubsItem.id }
                    ?: httpSubsItem).copy(mtime = System.currentTimeMillis()))
                call.respond(RpcOk())
            }
            post("/execSelector") {
                if (!A11yService.isRunning.value) {
                    throw RpcError("无障碍没有运行")
                }
                val gkdAction = call.receive<GkdAction>()
                call.respond(A11yService.execAction(gkdAction))
            }
        }
    }
}
