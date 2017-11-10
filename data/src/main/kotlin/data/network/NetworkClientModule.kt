package data.network

import dagger.Module
import dagger.Provides
import data.crash.FirebaseCrashReporterModule
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import reporter.CrashReporter
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module(includes = arrayOf(FirebaseCrashReporterModule::class))
internal class NetworkClientModule {
    @Provides
    @Singleton
    fun client(crashReporter: CrashReporter): OkHttpClient.Builder = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .addNetworkInterceptor { chain ->
                chain.request().let {
                    chain.proceed(it).also {
                        it.newBuilder().build().let { copy ->
                            val buffer = Buffer().also { copy.request().body()?.writeTo(it) }
                            crashReporter.report(IllegalMonitorStateException("""
                                Code: ${copy.code()}
                                Method: ${copy.request().method()}
                                Request body: ${buffer.readUtf8()}
                                Response body: ${copy.body()?.string()}
                                Cache-Control: ${copy.cacheControl().takeUnless { it.toString().isBlank() } ?: "EMPTY"}
                                Headers: ${copy.headers().takeUnless { it.toString().isBlank() } ?: "NONE"}
                                Url: ${copy.request().url().encodedPath()}
                                """))
                        }
                    }
                }
            }
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val TIMEOUT_SECONDS = 45L
    }
}
