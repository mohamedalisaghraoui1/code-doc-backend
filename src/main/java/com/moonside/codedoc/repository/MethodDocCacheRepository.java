package com.moonside.codedoc.repository;

import com.moonside.codedoc.model.MethodDocCacheEntry;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MethodDocCacheRepository extends MongoRepository<MethodDocCacheEntry, String> {
}