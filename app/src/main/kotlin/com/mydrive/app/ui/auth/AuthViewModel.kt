package com.mydrive.app.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginFormState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

data class SignUpFormState(
    val fullName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val pendingVerificationEmail: String? = null
)

enum class EmailVerificationPhase {
    Idle,
    SendingCode,
    CodeSent,
    Verifying,
    Verified,
    Error,
    ResendCooldown
}

data class EmailVerificationState(
    val email: String = "",
    val code: String = "",
    val phase: EmailVerificationPhase = EmailVerificationPhase.Idle,
    val cooldownSeconds: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null
) {
    val isVerifying: Boolean get() = phase == EmailVerificationPhase.Verifying
    val isSendingCode: Boolean get() = phase == EmailVerificationPhase.SendingCode
    val canResend: Boolean get() = cooldownSeconds <= 0 && !isSendingCode && !isVerifying
    val canVerify: Boolean get() = code.length == 6 && !isVerifying && !isSendingCode
}

data class ForgotPasswordFormState(
    val email: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _login = MutableStateFlow(LoginFormState())
    val login: StateFlow<LoginFormState> = _login.asStateFlow()

    private val _signUp = MutableStateFlow(SignUpFormState())
    val signUp: StateFlow<SignUpFormState> = _signUp.asStateFlow()

    private val _forgotPassword = MutableStateFlow(ForgotPasswordFormState())
    val forgotPassword: StateFlow<ForgotPasswordFormState> = _forgotPassword.asStateFlow()

    private val _verification = MutableStateFlow(EmailVerificationState())
    val verification: StateFlow<EmailVerificationState> = _verification.asStateFlow()

    private var cooldownJob: Job? = null

    fun setLoginEmail(value: String) {
        _login.update { it.copy(email = value, errorMessage = null) }
    }

    fun setLoginPassword(value: String) {
        _login.update { it.copy(password = value, errorMessage = null) }
    }

    fun setSignUpFullName(value: String) {
        _signUp.update { it.copy(fullName = value, errorMessage = null, infoMessage = null) }
    }

    fun setSignUpEmail(value: String) {
        _signUp.update { it.copy(email = value, errorMessage = null, infoMessage = null) }
    }

    fun setSignUpPassword(value: String) {
        _signUp.update { it.copy(password = value, errorMessage = null, infoMessage = null) }
    }

    fun setSignUpConfirmPassword(value: String) {
        _signUp.update { it.copy(confirmPassword = value, errorMessage = null, infoMessage = null) }
    }

    fun setForgotEmail(value: String) {
        _forgotPassword.update { it.copy(email = value, errorMessage = null, infoMessage = null) }
    }

    fun login() {
        val form = _login.value
        if (form.isSubmitting) return
        val email = form.email.trim()
        val password = form.password
        val validation = validateLogin(email, password)
        if (validation != null) {
            _login.update { it.copy(errorMessage = validation) }
            return
        }
        viewModelScope.launch {
            _login.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = authRepository.login(email, password)
            _login.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = result.exceptionOrNull()?.message,
                    password = if (result.isSuccess) "" else it.password
                )
            }
        }
    }

    fun signUp() {
        val form = _signUp.value
        if (form.isSubmitting) return
        val fullName = form.fullName.trim()
        val email = form.email.trim()
        val validation = validateSignUp(fullName, email, form.password, form.confirmPassword)
        if (validation != null) {
            _signUp.update { it.copy(errorMessage = validation, infoMessage = null) }
            return
        }
        viewModelScope.launch {
            _signUp.update { it.copy(isSubmitting = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.signUp(fullName, email, form.password)
            _signUp.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = result.exceptionOrNull()?.message,
                    infoMessage = null,
                    password = if (result.isSuccess) "" else it.password,
                    confirmPassword = if (result.isSuccess) "" else it.confirmPassword,
                    pendingVerificationEmail = if (result.isSuccess) email else null
                )
            }
            if (result.isSuccess) {
                beginVerification(email, startCooldown = true)
            }
        }
    }

    fun consumeVerificationNavigation() {
        _signUp.update { it.copy(pendingVerificationEmail = null) }
    }

    fun ensureVerificationEmail(email: String) {
        val normalized = email.trim()
        if (normalized.isBlank()) return
        if (_verification.value.email.equals(normalized, ignoreCase = true)) return
        beginVerification(normalized, startCooldown = _verification.value.cooldownSeconds <= 0)
    }

    fun setVerificationCode(value: String) {
        val digits = value.filter { it.isDigit() }.take(6)
        _verification.update {
            it.copy(
                code = digits,
                errorMessage = null,
                phase = if (it.phase == EmailVerificationPhase.Error || it.phase == EmailVerificationPhase.Verified) {
                    if (it.cooldownSeconds > 0) EmailVerificationPhase.ResendCooldown else EmailVerificationPhase.CodeSent
                } else {
                    it.phase
                }
            )
        }
    }

    fun verifyEmailCode() {
        val current = _verification.value
        if (!current.canVerify) {
            if (current.code.length != 6) {
                _verification.update {
                    it.copy(
                        phase = EmailVerificationPhase.Error,
                        errorMessage = "Enter the 6-digit code from your email."
                    )
                }
            }
            return
        }
        viewModelScope.launch {
            _verification.update {
                it.copy(
                    phase = EmailVerificationPhase.Verifying,
                    errorMessage = null,
                    infoMessage = null
                )
            }
            val result = authRepository.verifyEmailCode(current.email, current.code)
            if (result.isSuccess) {
                _verification.update {
                    it.copy(
                        phase = EmailVerificationPhase.Verified,
                        errorMessage = null,
                        infoMessage = null,
                        code = ""
                    )
                }
            } else {
                _verification.update {
                    it.copy(
                        phase = EmailVerificationPhase.Error,
                        errorMessage = result.exceptionOrNull()?.message ?: "That code is incorrect. Please try again."
                    )
                }
            }
        }
    }

    fun resendVerificationCode() {
        val current = _verification.value
        if (!current.canResend) return
        val email = current.email.ifBlank { _signUp.value.email.trim() }
        if (email.isBlank()) {
            _verification.update {
                it.copy(
                    phase = EmailVerificationPhase.Error,
                    errorMessage = "Enter a valid email address."
                )
            }
            return
        }
        viewModelScope.launch {
            _verification.update {
                it.copy(
                    phase = EmailVerificationPhase.SendingCode,
                    errorMessage = null,
                    infoMessage = null
                )
            }
            val result = authRepository.sendVerificationCode(email)
            if (result.isSuccess) {
                _verification.update {
                    it.copy(
                        email = email,
                        phase = EmailVerificationPhase.CodeSent,
                        infoMessage = "A new verification code was sent."
                    )
                }
                startCooldown()
            } else {
                _verification.update {
                    it.copy(
                        phase = EmailVerificationPhase.Error,
                        errorMessage = result.exceptionOrNull()?.message
                    )
                }
            }
        }
    }

    private fun beginVerification(email: String, startCooldown: Boolean) {
        _verification.update {
            it.copy(
                email = email,
                code = "",
                phase = if (startCooldown) EmailVerificationPhase.ResendCooldown else EmailVerificationPhase.CodeSent,
                errorMessage = null,
                infoMessage = null
            )
        }
        if (startCooldown) startCooldown()
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            _verification.update {
                it.copy(
                    cooldownSeconds = RESEND_COOLDOWN_SECONDS,
                    phase = if (it.phase == EmailVerificationPhase.Verifying) {
                        it.phase
                    } else {
                        EmailVerificationPhase.ResendCooldown
                    }
                )
            }
            while (_verification.value.cooldownSeconds > 0) {
                delay(1_000)
                val verifying = _verification.value.phase == EmailVerificationPhase.Verifying
                _verification.update { state ->
                    val next = (state.cooldownSeconds - 1).coerceAtLeast(0)
                    state.copy(
                        cooldownSeconds = next,
                        phase = when {
                            verifying -> EmailVerificationPhase.Verifying
                            next == 0 && state.phase == EmailVerificationPhase.ResendCooldown -> EmailVerificationPhase.CodeSent
                            else -> state.phase
                        }
                    )
                }
            }
        }
    }

    fun sendPasswordReset() {
        val current = _forgotPassword.value
        if (current.isSubmitting) return
        val email = current.email.trim()
        val validation = validateEmail(email)
        if (validation != null) {
            _forgotPassword.update { it.copy(errorMessage = validation, infoMessage = null) }
            return
        }
        viewModelScope.launch {
            _forgotPassword.update { it.copy(isSubmitting = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.sendPasswordReset(email)
            _forgotPassword.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = result.exceptionOrNull()?.message,
                    infoMessage = if (result.isSuccess) {
                        "If an account exists for that email, you'll receive a reset link."
                    } else {
                        null
                    }
                )
            }
        }
    }

    private fun validateLogin(email: String, password: String): String? {
        validateEmail(email)?.let { return it }
        if (password.isBlank()) return "Enter your password."
        return null
    }

    private fun validateSignUp(
        fullName: String,
        email: String,
        password: String,
        confirmPassword: String
    ): String? {
        if (fullName.isBlank()) return "Enter your full name."
        validateEmail(email)?.let { return it }
        if (password.isBlank()) return "Enter a password."
        if (password.length < 6) return "Choose a stronger password."
        if (password != confirmPassword) return "Passwords do not match."
        return null
    }

    private fun validateEmail(email: String): String? {
        if (email.isBlank()) return "Enter your email."
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) return "Enter a valid email address."
        return null
    }

    companion object {
        private const val RESEND_COOLDOWN_SECONDS = 60

        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(authRepository) as T
                }
            }
    }
}
