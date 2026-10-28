package com.cryptedgallery.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SecurityManager(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128
        
        private const val PREF_PASSWORD_HASH = "password_hash"
        private const val PREF_ATTEMPTS = "failed_attempts"
        private const val PREF_LOCKOUT_UNTIL = "lockout_until"
        private const val PREF_LOCKOUT_LEVEL = "lockout_level"
    }

    fun isPasswordSet(): Boolean = encryptedPrefs.contains(PREF_PASSWORD_HASH)

    fun setPassword(password: String) {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val hash = deriveKey(password, salt)
        val storedValue = Base64.getEncoder().encodeToString(salt) + ":" + 
                         Base64.getEncoder().encodeToString(hash)
        encryptedPrefs.edit().putString(PREF_PASSWORD_HASH, storedValue).apply()
    }

    fun verifyPassword(password: String): Boolean {
        if (isLockedOut()) return false

        val stored = encryptedPrefs.getString(PREF_PASSWORD_HASH, null) ?: return false
        val parts = stored.split(":")
        val salt = Base64.getDecoder().decode(parts[0])
        val hash = Base64.getDecoder().decode(parts[1])
        
        val calculatedHash = deriveKey(password, salt)
        val isValid = calculatedHash.contentEquals(hash)

        if (isValid) {
            resetAttempts()
        } else {
            incrementAttempts()
        }
        
        return isValid
    }

    fun isLockedOut(): Boolean {
        val lockoutUntil = encryptedPrefs.getLong(PREF_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < lockoutUntil
    }

    fun getRemainingLockoutTimeMs(): Long {
        val lockoutUntil = encryptedPrefs.getLong(PREF_LOCKOUT_UNTIL, 0L)
        return (lockoutUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun getAttemptsLeftInCycle(): Int {
        val currentAttempts = encryptedPrefs.getInt(PREF_ATTEMPTS, 0)
        return 3 - (currentAttempts % 3)
    }

    fun getLastLockoutDurationMs(): Long {
        val currentLevel = encryptedPrefs.getInt(PREF_LOCKOUT_LEVEL, 0)
        if (currentLevel == 0) return 3 * 60 * 1000L // Default base
        val multiplier = Math.pow(2.0, (currentLevel - 1).toDouble()).toLong()
        return 3 * multiplier * 60 * 1000
    }

    private fun incrementAttempts() {
        val currentAttempts = encryptedPrefs.getInt(PREF_ATTEMPTS, 0) + 1
        encryptedPrefs.edit().putInt(PREF_ATTEMPTS, currentAttempts).apply()
        
        if (currentAttempts % 3 == 0) {
            val currentLevel = encryptedPrefs.getInt(PREF_LOCKOUT_LEVEL, 0) + 1
            encryptedPrefs.edit().putInt(PREF_LOCKOUT_LEVEL, currentLevel).apply()
            
            // Formula: 3 * 2^(level - 1) minutes
            val multiplier = Math.pow(2.0, (currentLevel - 1).toDouble()).toLong()
            val lockoutMinutes = 3 * multiplier
            val lockoutTimeMs = System.currentTimeMillis() + (lockoutMinutes * 60 * 1000)
            
            encryptedPrefs.edit().putLong(PREF_LOCKOUT_UNTIL, lockoutTimeMs).apply()
        }
    }

    private fun resetAttempts() {
        encryptedPrefs.edit()
            .putInt(PREF_ATTEMPTS, 0)
            .putLong(PREF_LOCKOUT_UNTIL, 0L)
            .putInt(PREF_LOCKOUT_LEVEL, 0)
            .apply()
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encrypt(data: ByteArray, password: String): String {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val key = SecretKeySpec(deriveKey(password, salt), "AES")
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        
        val encryptedData = cipher.doFinal(data)
        
        return Base64.getEncoder().encodeToString(salt) + ":" +
               Base64.getEncoder().encodeToString(iv) + ":" +
               Base64.getEncoder().encodeToString(encryptedData)
    }

    fun decrypt(encryptedString: String, password: String): ByteArray {
        val parts = encryptedString.split(":")
        val salt = Base64.getDecoder().decode(parts[0])
        val iv = Base64.getDecoder().decode(parts[1])
        val encryptedData = Base64.getDecoder().decode(parts[2])
        
        val key = SecretKeySpec(deriveKey(password, salt), "AES")
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        
        return cipher.doFinal(encryptedData)
    }
}
