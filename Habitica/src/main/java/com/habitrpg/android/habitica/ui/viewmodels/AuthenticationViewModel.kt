package com.habitrpg.android.habitica.ui.viewmodels

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.UserRecoverableException
import com.habitrpg.android.habitica.BuildConfig
import com.habitrpg.android.habitica.HabiticaBaseApplication
import com.habitrpg.android.habitica.R
import com.habitrpg.android.habitica.api.HostConfig
import com.habitrpg.android.habitica.data.ApiClient
import com.habitrpg.android.habitica.data.UserRepository
import com.habitrpg.android.habitica.extensions.addCloseButton
import com.habitrpg.android.habitica.helpers.KeyHelper
import com.habitrpg.android.habitica.helpers.RxErrorHandler
import com.habitrpg.android.habitica.helpers.SignInWithAppleResult
import com.habitrpg.android.habitica.helpers.SignInWithAppleService
import com.habitrpg.android.habitica.models.auth.UserAuthResponse
import com.habitrpg.android.habitica.models.user.User
import com.habitrpg.android.habitica.proxy.AnalyticsManager
import com.habitrpg.android.habitica.ui.views.dialogs.HabiticaAlertDialog
import com.willowtreeapps.signinwithapplebutton.SignInWithAppleConfiguration
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.exceptions.Exceptions
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import javax.inject.Inject

class AuthenticationViewModel() {
    @Inject
    internal lateinit var apiClient: ApiClient
    @Inject
    internal lateinit var userRepository: UserRepository
    @Inject
    internal lateinit var sharedPrefs: SharedPreferences
    @Inject
    internal lateinit var hostConfig: HostConfig
    @Inject
    internal lateinit var analyticsManager: AnalyticsManager
    @Inject
    @JvmField
    var keyHelper: KeyHelper? = null

    private var compositeSubscription = CompositeDisposable()


    private var callbackManager = CallbackManager.Factory.create()
    var googleEmail: String? = null
    private var loginManager = LoginManager.getInstance()

    init {
        HabiticaBaseApplication.userComponent?.inject(this)
    }

    fun connectApple(fragmentManager: FragmentManager, onSuccess: (UserAuthResponse) -> Unit) {
        val configuration = SignInWithAppleConfiguration(
            clientId = BuildConfig.APPLE_AUTH_CLIENT_ID,
            redirectUri = "${hostConfig.address}/api/v4/user/auth/apple",
            scope = "name email"
        )
        val fragmentTag = "SignInWithAppleButton-SignInWebViewDialogFragment"

        SignInWithAppleService(fragmentManager, fragmentTag, configuration) { result ->
            when (result) {
                is SignInWithAppleResult.Success -> {
                    val response = UserAuthResponse()
                    response.id = result.userID
                    response.apiToken = result.apiKey
                    response.newUser = result.newUser
                    onSuccess(response)
                }
            }
        }.show()
    }

    fun setupFacebookLogin(onSuccess: (UserAuthResponse) -> Unit) {
        callbackManager = CallbackManager.Factory.create()
        loginManager.registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(loginResult: LoginResult) {
                    val accessToken = AccessToken.getCurrentAccessToken()
                    compositeSubscription.add(
                        apiClient.connectSocial("facebook", accessToken?.userId ?: "", accessToken?.token ?: "")
                            .subscribe({
                                       onSuccess(it)
                            }, RxErrorHandler.handleEmptyError())
                    )
                }

                override fun onCancel() { /* no-on */ }

                override fun onError(exception: FacebookException) {
                    RxErrorHandler.reportError(exception)
                }
            }
        )
    }

    fun handleFacebookLogin(activity: Activity) {
        loginManager.logInWithReadPermissions(activity, listOf("user_friends"))
    }

    fun handleFacebookLogin(fragment: Fragment) {
        loginManager.logInWithReadPermissions(fragment, listOf("user_friends"))
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?, onSuccess: (UserAuthResponse) -> Unit) {
        callbackManager.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FacebookSdk.getCallbackRequestCodeOffset()) {
            // This is necessary because the regular login callback is not called for some reason
            val accessToken = AccessToken.getCurrentAccessToken()
            if (accessToken?.token != null) {
                compositeSubscription.add(
                    apiClient.connectSocial("facebook", accessToken.userId, accessToken.token)
                        .subscribe({
                                   onSuccess(it)
                        }, { })
                )
            }
        }
    }

    fun handleGoogleLogin(
        activity: Activity,
        pickAccountResult: ActivityResultLauncher<Intent>
    ) {
        if (!checkPlayServices(activity)) {
            return
        }
        val accountTypes = arrayOf("com.google")
        val intent = AccountManager.newChooseAccountIntent(
            null, null,
            accountTypes, true, null, null, null, null
        )
        try {
            pickAccountResult.launch(intent)
        } catch (e: ActivityNotFoundException) {
            val alert = HabiticaAlertDialog(activity)
            alert.setTitle(R.string.authentication_error_title)
            alert.setMessage(R.string.google_services_missing)
            alert.addCloseButton()
            alert.show()
        }
    }

    fun handleGoogleLoginResult(
        activity: Activity,
        recoverFromPlayServicesErrorResult: ActivityResultLauncher<Intent>?,
    onSuccess: (User, Boolean) -> Unit
    ) {
        val scopesString = Scopes.PROFILE + " " + Scopes.EMAIL
        val scopes = "oauth2:$scopesString"
        var newUser = false
        compositeSubscription.add(
            Flowable.defer {
                try {
                    @Suppress("Deprecation")
                    return@defer Flowable.just(GoogleAuthUtil.getToken(activity, googleEmail, scopes))
                } catch (e: IOException) {
                    throw Exceptions.propagate(e)
                } catch (e: GoogleAuthException) {
                    throw Exceptions.propagate(e)
                } catch (e: UserRecoverableException) {
                    return@defer Flowable.empty()
                }
            }
                .subscribeOn(Schedulers.io())
                .flatMap { token -> apiClient.connectSocial("google", googleEmail ?: "", token) }
                .doOnNext {
                    newUser = it.newUser
                    handleAuthResponse(it)
                }
                .flatMap { userRepository.retrieveUser(true, true) }
                .subscribe(
                    {
                    onSuccess(it, newUser)
                    },
                    { throwable ->
                        if (recoverFromPlayServicesErrorResult == null) return@subscribe
                        throwable.cause?.let {
                            if (GoogleAuthException::class.java.isAssignableFrom(it.javaClass)) {
                                handleGoogleAuthException(throwable.cause as GoogleAuthException,
                                activity,
                                recoverFromPlayServicesErrorResult)
                            }
                        }
                    }
                )
        )
    }

    private fun handleGoogleAuthException(
        e: Exception,
        activity: Activity,
        recoverFromPlayServicesErrorResult: ActivityResultLauncher<Intent>
    ) {
        if (e is GooglePlayServicesAvailabilityException) {
            GoogleApiAvailability.getInstance()
            GooglePlayServicesUtil.showErrorDialogFragment(
                e.connectionStatusCode,
                activity,
                null,
                AuthenticationViewModel.REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR
            ) {
            }
            return
        } else if (e is UserRecoverableAuthException) {
            // Unable to authenticate, such as when the user has not yet granted
            // the app access to the account, but the user can fix this.
            // Forward the user to an activity in Google Play services.
            val intent = e.intent
            recoverFromPlayServicesErrorResult.launch(intent)
            return
        }

    }


    private fun checkPlayServices(activity: Activity): Boolean {
        val googleAPI = GoogleApiAvailability.getInstance()
        val result = googleAPI.isGooglePlayServicesAvailable(activity)
        if (result != ConnectionResult.SUCCESS) {
            if (googleAPI.isUserResolvableError(result)) {
                googleAPI.getErrorDialog(activity, result,
                    AuthenticationViewModel.PLAY_SERVICES_RESOLUTION_REQUEST
                ).show()
            }
            return false
        }

        return true
    }

    fun handleAuthResponse(userAuthResponse: UserAuthResponse) {
        try {
            saveTokens(userAuthResponse.apiToken, userAuthResponse.id)
        } catch (e: Exception) {
            analyticsManager.logException(e)
        }

        HabiticaBaseApplication.reloadUserComponent()
    }

    @Throws(Exception::class)
    private fun saveTokens(api: String, user: String) {
        this.apiClient.updateAuthenticationCredentials(user, api)
        sharedPrefs.edit {
            putString("UserID", user)
            val encryptedKey = if (keyHelper != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    keyHelper?.encrypt(api)
                } catch (e: Exception) {
                    null
                }
            } else null
            if (encryptedKey?.length ?: 0 > 5) {
                putString(user, encryptedKey)
            } else {
                // Something might have gone wrong with encryption, so fall back to this.
                putString("APIToken", api)
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_RECOVER_FROM_PLAY_SERVICES_ERROR = 1001
        private const val PLAY_SERVICES_RESOLUTION_REQUEST = 9000
    }
}