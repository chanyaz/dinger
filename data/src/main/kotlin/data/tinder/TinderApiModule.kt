package data.tinder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.facebook.AccessToken
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import dagger.Module
import dagger.Provides
import data.RootModule
import data.account.AccountModule
import data.account.AppAccountAuthenticator
import data.crash.FirebaseCrashReporterModule
import data.network.NetworkClientModule
import data.network.NetworkModule
import data.notification.NotificationManager
import data.notification.NotificationManagerModule
import domain.login.TinderLoginUseCase
import io.reactivex.Single
import io.reactivex.observers.DisposableCompletableObserver
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.stoyicker.dinger.data.BuildConfig
import org.stoyicker.dinger.data.R
import reporter.CrashReporter
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module(includes = arrayOf(
        AccountModule::class,
        FirebaseCrashReporterModule::class,
        NetworkClientModule::class,
        NetworkModule::class,
        NotificationManagerModule::class,
        RootModule::class))
internal class TinderApiModule {
    @Provides
    @Singleton
    fun tinderApi(
            context: Context,
            clientBuilder: OkHttpClient.Builder,
            retrofitBuilder: Retrofit.Builder,
            appAccountManager: AppAccountAuthenticator,
            notificationManager: NotificationManager,
            crashReporter: CrashReporter): TinderApi = retrofitBuilder
            .client(clientBuilder.addInterceptor {
                it.proceed(it.request().newBuilder().addHeaders(appAccountManager).build())
            }.authenticator { _, response -> when {
                !response.request().header(HEADER_AUTH_TRIED).isNullOrBlank() -> {
                    Thread.sleep(TimeUnit.MILLISECONDS.convert(1, TimeUnit.MINUTES))
                    response.request()
                }
                response.code() == 401 -> appAccountManager.let {
                    val facebookToken: AccessToken? = AccessToken.getCurrentAccessToken()
                    if (facebookToken != null) {
                        Single.create<AccessToken> { emitter ->
                            if (facebookToken.isExpired) {
                                AccessToken.refreshCurrentAccessTokenAsync(
                                        object : AccessToken.AccessTokenRefreshCallback {
                                            @Suppress("FunctionName")
                                            override fun OnTokenRefreshed(
                                                    accessToken: AccessToken) {
                                                emitter.onSuccess(accessToken)
                                            }

                                            @Suppress("FunctionName")
                                            override fun OnTokenRefreshFailed(
                                                    exception: FacebookException) {
                                                emitter.onError(exception)
                                            }
                                        })
                            } else {
                                emitter.onSuccess(facebookToken)
                            }
                        }.run {
                            try {
                                blockingGet().run {
                                    AccessToken.setCurrentAccessToken(this)
                                    var request: Request? = null
                                    val oldToken = appAccountManager.getTinderAccountToken()
                                    TinderLoginUseCase(
                                            facebookId = userId,
                                            facebookToken = token,
                                            postExecutionScheduler = Schedulers.trampoline())
                                            .execute(object : DisposableCompletableObserver() {
                                                override fun onComplete() {
                                                    crashReporter.report(Throwable("Trying to authorize via token ${appAccountManager.getTinderAccountToken()}, old one was $oldToken"))
                                                    request = response.request().newBuilder()
                                                            .addHeader(
                                                                    HEADER_AUTH_TRIED, "true")
                                                            .addHeaders(appAccountManager)
                                                            .build()
                                                }

                                                override fun onError(error: Throwable) {
                                                    crashReporter.report(error)
                                                }
                                            })
                                    request
                                }
                            } catch (exception: FacebookException) {
                                crashReporter.report(exception)
                                finishAccount(context, appAccountManager, notificationManager)
                                null
                            }
                        }
                    } else {
                        crashReporter.report(Throwable("Not authorizing because the facebookToken was null"))
                        finishAccount(context, appAccountManager, notificationManager)
                        null
                    }
                }
                else -> {
                    crashReporter.report(Throwable("Not authorizing and finishing account because the code was not 401 and the header was there??!?!"))
                    finishAccount(context, appAccountManager, notificationManager)
                    null
                }
            }
            }.build())
            .baseUrl(TinderApi.BASE_URL)
            .build()
            .create(TinderApi::class.java)

    private companion object {
        const val HEADER_AUTH_TRIED = "auth-tried"
    }
}

private fun Request.Builder.addHeaders(appAccountManager: AppAccountAuthenticator) = apply {
            appAccountManager.getTinderAccountToken()?.let {
                addHeader(TinderApi.HEADER_AUTH, it)
                addHeader(TinderApi.HEADER_CONTENT_TYPE, TinderApi.CONTENT_TYPE_JSON)
                addHeader(TinderApi.HEADER_PLATFORM, BuildConfig.PLATFORM_ANDROID)
            }
        }

private fun finishAccount(
        context: Context,
        appAccountAuthenticator: AppAccountAuthenticator,
        notificationManager: NotificationManager) {
    LoginManager.getInstance().logOut()
    appAccountAuthenticator.removeAccount()
    notificationManager.notify(
            channelName = R.string.authentication_notification_channel_name,
            title = R.string.authentication_notification_title,
            body = R.string.authentication_notification_body,
            category = NotificationManager.CATEGORY_RECOMMENDATION,
            priority = NotificationManager.PRIORITY_HIGH,
            clickHandler = PendingIntent.getActivity(
                    context,
                    1,
                    Intent("org.stoyicker.action.HOME"),
                    PendingIntent.FLAG_UPDATE_CURRENT))
}
