package com.mydrive.app.ui.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mydrive.app.data.repository.AuthRepository
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
    val infoMessage: String? = null
)

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
            val confirmationRequired = result.exceptionOrNull() is AuthRepository.EmailConfirmationRequired
            _signUp.update {
                it.copy(
                    isSubmitting = false,
                    errorMessage = if (confirmationRequired) null else result.exceptionOrNull()?.message,
                    infoMessage = if (confirmationRequired) {
                        result.exceptionOrNull()?.message
                    } else {
                        null
                    },
                    password = if (result.isSuccess || confirmationRequired) "" else it.password,
                    confirmPassword = if (result.isSuccess || confirmationRequired) "" else it.confirmPassword
                )
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
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(authRepository) as T
                }
            }
    }
}
