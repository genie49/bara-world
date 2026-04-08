package com.bara.test

import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate

object DatabaseCleaner {

    fun clean(mongoTemplate: MongoTemplate) {
        mongoTemplate.db.listCollectionNames().forEach { name ->
            mongoTemplate.db.getCollection(name).deleteMany(Document())
        }
    }
}
