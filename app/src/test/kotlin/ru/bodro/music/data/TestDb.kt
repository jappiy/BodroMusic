package ru.bodro.music.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ru.bodro.music.data.local.BodroDatabase

fun inMemoryDb(): BodroDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BodroDatabase::class.java)
        .allowMainThreadQueries()
        .build()
