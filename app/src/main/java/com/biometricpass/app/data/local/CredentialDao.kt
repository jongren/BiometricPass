package com.biometricpass.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT COUNT(*) FROM credentials")
    suspend fun countAll(): Int

    /** Credentials bound to exactly this App package. */
    @Query("SELECT * FROM credentials WHERE packageName = :packageName ORDER BY updatedAt DESC")
    suspend fun findByPackage(packageName: String): List<CredentialEntity>

    /**
     * Every credential that has a website domain. Domain matching (sub-domains + Public Suffix List)
     * is done in Kotlin by `WebDomainMatcher`, avoiding SQL `LIKE` wildcard / case pitfalls.
     */
    @Query("SELECT * FROM credentials WHERE webDomain != '' ORDER BY updatedAt DESC")
    suspend fun findWithWebDomain(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CredentialEntity): Long

    @Update
    suspend fun update(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: Long)
}
