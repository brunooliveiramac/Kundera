/*******************************************************************************
 * * Copyright 2011 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.mongodb;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.impetus.client.mongodb.query.MongoDBQuery;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.DBType;
import com.impetus.kundera.index.IndexManager;
import com.impetus.kundera.metadata.KunderaMetadataManager;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.persistence.EntityResolver;
import com.impetus.kundera.proxy.EnhancedEntity;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

/**
 * CLient class for MongoDB database.
 * 
 * @author impetusopensource
 */
public class MongoDBClient implements Client
{

    /** The contact node. */
    private String contactNode;

    /** The default port. */
    private String defaultPort;

    /** The db name. */
    private String dbName;

    /** The is connected. */
    private boolean isConnected;

    /** The em. */
    private EntityManager em;

    /** The mongo. */
    Mongo mongo;

    /** The mongo db. */
    DB mongoDb;

    /** The log. */
    private static Log log = LogFactory.getLog(MongoDBClient.class);

    @Override
    public void writeData(EnhancedEntity enhancedEntity) throws Exception
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), enhancedEntity
                .getEntity().getClass());

        String dbName = entityMetadata.getSchema();
        String documentName = entityMetadata.getTableName();
        String key = enhancedEntity.getId();

        log.debug("Checking whether record already exist for " + dbName + "." + documentName + " for " + key);
        Object entity = loadData(enhancedEntity.getClass(), key);
        if (entity != null)
        {
            log.debug("Updating data into " + dbName + "." + documentName + " for " + key);
            DBCollection dbCollection = mongoDb.getCollection(documentName);

            BasicDBObject searchQuery = new BasicDBObject();
            searchQuery.put(entityMetadata.getIdColumn().getName(), key);
            BasicDBObject updatedDocument = new MongoDBDataHandler(this, getPersistenceUnit()).getDocumentFromEntity(
                    em, entityMetadata, enhancedEntity);
            dbCollection.update(searchQuery, updatedDocument);

        }
        else
        {
            log.debug("Inserting data into " + dbName + "." + documentName + " for " + key);
            DBCollection dbCollection = mongoDb.getCollection(documentName);

            BasicDBObject document = new MongoDBDataHandler(this, getPersistenceUnit()).getDocumentFromEntity(em,
                    entityMetadata, enhancedEntity);
            dbCollection.insert(document);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.impetus.kundera.Client#loadColumns(com.impetus.kundera.ejb.
     * EntityManager, java.lang.Class, java.lang.String, java.lang.String,
     * java.lang.String, com.impetus.kundera.metadata.EntityMetadata)
     */
    @Override
    public <E> E loadData(Class<E> entityClass, String key) throws Exception
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), entityClass);

        log.debug("Fetching data from " + entityMetadata.getTableName() + " for PK " + key);

        DBCollection dbCollection = mongoDb.getCollection(entityMetadata.getTableName());

        BasicDBObject query = new BasicDBObject();
        query.put(entityMetadata.getIdColumn().getName(), key);

        DBCursor cursor = dbCollection.find(query);
        DBObject fetchedDocument = null;

        if (cursor.hasNext())
        {
            fetchedDocument = cursor.next();
        }
        else
        {
            return null;
        }

        Object entity = new MongoDBDataHandler(this, getPersistenceUnit()).getEntityFromDocument(em,
                entityMetadata.getEntityClazz(), entityMetadata, fetchedDocument);

        return (E) entity;
    }

    @Override
    public <E> List<E> loadData(Class<E> entityClass, String... keys) throws Exception
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), entityClass);

        log.debug("Fetching data from " + entityMetadata.getTableName() + " for Keys " + keys);

        DBCollection dbCollection = mongoDb.getCollection(entityMetadata.getTableName());

        BasicDBObject query = new BasicDBObject();
        query.put(entityMetadata.getIdColumn().getName(), new BasicDBObject("$in", keys));

        DBCursor cursor = dbCollection.find(query);

        List entities = new ArrayList<E>();
        while (cursor.hasNext())
        {
            DBObject fetchedDocument = cursor.next();
            Object entity = new MongoDBDataHandler(this, getPersistenceUnit()).getEntityFromDocument(em,
                    entityMetadata.getEntityClazz(), entityMetadata, fetchedDocument);
            entities.add(entity);
        }
        return entities;
    }

    /**
     * Loads columns from multiple rows restricting results to conditions stored
     * in <code>filterClauseQueue</code>.
     * 
     * @param <E>
     *            the element type
     * @param em
     *            the em
     * @param m
     *            the m
     * @param filterClauseQueue
     *            the filter clause queue
     * @return the list
     * @throws Exception
     *             the exception
     */
    public <E> List<E> loadData(Query query) throws Exception
    {
        // TODO Resolve the workaround
        EntityMetadata entityMetadata = KunderaMetadataManager
                .getEntityMetadata(getPersistenceUnit(), query.getClass());

        String documentName = entityMetadata.getTableName();
        String dbName = entityMetadata.getSchema();
        Class clazz = entityMetadata.getEntityClazz();

        DBCollection dbCollection = mongoDb.getCollection(documentName);

        MongoDBQuery mongoDBQuery = (MongoDBQuery) query;
        Queue filterClauseQueue = mongoDBQuery.getFilterClauseQueue();
        String result = mongoDBQuery.getResult();

        List entities = new ArrayList<E>();

        // If User wants search on a column within a particular super column,
        // fetch that embedded object collection only
        // otherwise retrieve whole entity
        // TODO: improve code
        if (result.indexOf(".") >= 0)
        {

            entities.addAll(new MongoDBDataHandler(this, getPersistenceUnit()).getEmbeddedObjectList(dbCollection,
                    entityMetadata, documentName, query));

        }
        else
        {
            log.debug("Fetching data from " + documentName + " for Filter " + filterClauseQueue);

            BasicDBObject mongoQuery = new MongoDBDataHandler(this, getPersistenceUnit()).createMongoQuery(
                    entityMetadata, filterClauseQueue);

            DBCursor cursor = dbCollection.find(mongoQuery);

            while (cursor.hasNext())
            {
                DBObject fetchedDocument = cursor.next();
                Object entity = new MongoDBDataHandler(this, getPersistenceUnit()).getEntityFromDocument(em, clazz,
                        entityMetadata, fetchedDocument);
                entities.add(entity);
            }
        }

        return entities;
    }

    @Override
    public void delete(EnhancedEntity enhancedEntity) throws Exception
    {
        EntityMetadata entityMetadata = KunderaMetadataManager.getEntityMetadata(getPersistenceUnit(), enhancedEntity
                .getEntity().getClass());
        DBCollection dbCollection = mongoDb.getCollection(entityMetadata.getTableName());

        // Find the DBObject to remove first
        BasicDBObject query = new BasicDBObject();
        query.put(entityMetadata.getSchema(), enhancedEntity.getId());

        DBCursor cursor = dbCollection.find(query);
        DBObject documentToRemove = null;

        if (cursor.hasNext())
        {
            documentToRemove = cursor.next();
        }
        else
        {
            throw new PersistenceException("Can't remove Row# " + enhancedEntity.getId() + " for "
                    + entityMetadata.getTableName() + " because record doesn't exist.");
        }

        dbCollection.remove(documentToRemove);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#connect()
     */
    @Override
    public void connect()
    {
        if (!isConnected)
        {
            log.info(">>> Connecting to MONGODB at " + contactNode + " on port " + defaultPort);
            try
            {
                mongo = new Mongo(contactNode, Integer.parseInt(defaultPort));
                mongoDb = mongo.getDB(dbName);
                isConnected = true;
                log.info("CONNECTED to MONGODB at " + contactNode + " on port " + defaultPort);
            }
            catch (NumberFormatException e)
            {
                log.error("Invalid format for MONGODB port, Unale to connect!" + "; Details:" + e.getMessage());
            }
            catch (UnknownHostException e)
            {
                log.error("Unable to connect to MONGODB at host " + contactNode + "; Details:" + e.getMessage());
            }
            catch (MongoException e)
            {
                log.error("Unable to connect to MONGODB; Details:" + e.getMessage());
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#shutdown()
     */
    @Override
    public void shutdown()
    {
        if (isConnected && mongo != null)
        {
            log.info("Closing connection to MONGODB at " + contactNode + " on port " + defaultPort);
            mongo.close();
            log.info("Connection to MONGODB at " + contactNode + " on port " + defaultPort + " closed");
        }
        else
        {
            log.warn("Can't close connection to MONGODB, it was already disconnected");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#getType()
     */
    @Override
    public DBType getType()
    {
        return DBType.MONGODB;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setContactNodes(java.lang.String[])
     */
    @Override
    public void setContactNodes(String... contactNodes)
    {
        this.contactNode = contactNodes[0];
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setDefaultPort(int)
     */
    @Override
    public void setDefaultPort(int defaultPort)
    {
        this.defaultPort = String.valueOf(defaultPort);
    }

    // For MongoDB, keyspace means DB name
    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.Client#setKeySpace(java.lang.String)
     */
    @Override
    public void setSchema(String keySpace)
    {
        this.dbName = keySpace;
    }

    /**
     * Creates the index.
     * 
     * @param collectionName
     *            the collection name
     * @param columnList
     *            the column list
     * @param order
     *            the order
     */
    public void createIndex(String collectionName, List<String> columnList, int order)
    {
        DBCollection coll = mongoDb.getCollection(collectionName);

        List<DBObject> indexes = coll.getIndexInfo(); // List of all current
        // indexes on collection
        Set<String> indexNames = new HashSet<String>(); // List of all current
        // index names
        for (DBObject index : indexes)
        {
            BasicDBObject obj = (BasicDBObject) index.get("key");
            Set<String> set = obj.keySet(); // Set containing index name which
            // is key
            indexNames.addAll(set);
        }

        // Create index if not already created
        for (String columnName : columnList)
        {
            if (!indexNames.contains(columnName))
            {
                coll.createIndex(new BasicDBObject(columnName, order));
            }
        }
    }

    @Override
    public Query getQuery(String queryString)
    {
        return null;
    }

    @Override
    public <E> List<E> loadData(Class<E> entityClass, Map<String, String> col) throws Exception
    {
        throw new NotImplementedException("Not yet implemented");
    }

    @Override
    public String getPersistenceUnit()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public IndexManager getIndexManager()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public EntityResolver getEntityResolver()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setEntityResolver(EntityResolver entityResolver)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setPersistenceUnit(String persistenceUnit)
    {
        // TODO Auto-generated method stub

    }

}
