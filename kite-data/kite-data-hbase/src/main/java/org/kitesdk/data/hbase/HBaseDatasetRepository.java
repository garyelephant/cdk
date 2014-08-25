/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.data.hbase;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.URI;
import org.apache.hadoop.hbase.HConstants;
import org.kitesdk.data.DatasetDescriptor;
import org.kitesdk.data.DatasetIOException;
import org.kitesdk.data.DatasetOperationException;
import org.kitesdk.data.RandomAccessDataset;
import org.kitesdk.data.hbase.avro.GenericAvroDao;
import org.kitesdk.data.hbase.avro.SpecificAvroDao;
import org.kitesdk.data.hbase.impl.Dao;
import org.kitesdk.data.hbase.impl.SchemaManager;
import org.kitesdk.data.hbase.manager.DefaultSchemaManager;
import org.kitesdk.data.spi.AbstractDatasetRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.avro.specific.SpecificRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTablePool;
import org.kitesdk.data.spi.URIBuilder;

public class HBaseDatasetRepository extends AbstractDatasetRepository {

  private HTablePool tablePool;
  private SchemaManager schemaManager;
  private HBaseMetadataProvider metadataProvider;
  private final URI repositoryUri;

  HBaseDatasetRepository(HBaseAdmin hBaseAdmin, HTablePool tablePool, URI repositoryUri) {
    this.tablePool = tablePool;
    this.schemaManager = new DefaultSchemaManager(tablePool);
    this.metadataProvider = new HBaseMetadataProvider(hBaseAdmin, schemaManager);
    this.repositoryUri = repositoryUri;
  }

  @Override
  public <E> RandomAccessDataset<E> create(String name, DatasetDescriptor descriptor, Class<E> type) {
    Preconditions.checkNotNull(name, "Dataset name cannot be null");
    Preconditions.checkNotNull(descriptor, "Descriptor cannot be null");

    DatasetDescriptor newDescriptor = metadataProvider.create(name, descriptor);
    return newDataset(name, newDescriptor, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> RandomAccessDataset<E> create(String name, DatasetDescriptor descriptor) {
    return (RandomAccessDataset<E>) create(name, descriptor, Object.class);
  }

  @Override
  public <E> RandomAccessDataset<E> update(String name, DatasetDescriptor descriptor, Class<E> type) {
    Preconditions.checkNotNull(name, "Dataset name cannot be null");
    Preconditions.checkNotNull(descriptor, "Descriptor cannot be null");

    DatasetDescriptor newDescriptor = metadataProvider.update(name, descriptor);
    return newDataset(name, newDescriptor, type);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> RandomAccessDataset<E> update(String name, DatasetDescriptor descriptor) {
    return (RandomAccessDataset<E>) update(name, descriptor, Object.class);
  }

  @Override
  public <E> RandomAccessDataset<E> load(String name, Class<E> type) {
    Preconditions.checkNotNull(name, "Dataset name cannot be null");

    String tableName = HBaseMetadataProvider.getTableName(name);
    String entityName = HBaseMetadataProvider.getEntityName(name);
    if (entityName.contains(".")) {
      List<DatasetDescriptor> descriptors = new ArrayList<DatasetDescriptor>();
      for (String subEntityName : entityName.split("\\.")) {
        DatasetDescriptor descriptor = metadataProvider.load(tableName + "." + subEntityName);
        descriptors.add(descriptor);
      }
      return newCompositeDataset(name, tableName, descriptors, type);
    } else {
      DatasetDescriptor descriptor = metadataProvider.load(name);
      return newDataset(name, descriptor, type);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <E> RandomAccessDataset<E> load(String name) {
    return (RandomAccessDataset<E>) load(name, Object.class);
  }

  @Override
  public URI getUri() {
    return repositoryUri;
  }

  @SuppressWarnings("unchecked")
  private <E> RandomAccessDataset<E> newCompositeDataset(String name, String tableName,
      List<DatasetDescriptor> descriptors, Class<E> type) {
    List<Class<SpecificRecord>> subEntityClasses = new ArrayList<Class<SpecificRecord>>();
    for (DatasetDescriptor descriptor : descriptors) {
      try {
        Class<SpecificRecord> subEntityClass = (Class<SpecificRecord>) Class
            .forName(descriptor.getSchema().getFullName());
        subEntityClasses.add(subEntityClass);
      } catch (ClassNotFoundException e) {
        throw new DatasetOperationException("Failed to resolve sub-type", e);
      }
    }
    Dao dao = SpecificAvroDao.buildCompositeDaoWithEntityManager(tablePool,
        tableName, subEntityClasses, schemaManager);
    return new DaoDataset<E>(name, dao, descriptors.get(0),
        new URIBuilder(repositoryUri, name).build(), type);
  }

  @SuppressWarnings("unchecked")
  private <E> RandomAccessDataset<E> newDataset(String name, DatasetDescriptor descriptor, Class<E> type) {
    // TODO: use descriptor.getFormat() to decide type of DAO (Avro vs. other)
    String tableName = HBaseMetadataProvider.getTableName(name);
    String entityName = HBaseMetadataProvider.getEntityName(name);
    Dao dao;
    if (isSpecific(descriptor)) {
      dao = new SpecificAvroDao(tablePool, tableName, entityName, schemaManager);
    } else {
      dao = new GenericAvroDao(tablePool, tableName, entityName, schemaManager);
    }
    return new DaoDataset(name, dao, descriptor,
        new URIBuilder(repositoryUri, name).build(), type);
  }

  private static boolean isSpecific(DatasetDescriptor descriptor) {
    try {
      Class.forName(descriptor.getSchema().getFullName());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public boolean delete(String name) {
    Preconditions.checkNotNull(name, "Dataset name cannot be null");

    return metadataProvider.delete(name);
  }

  @Override
  public boolean exists(String name) {
    Preconditions.checkNotNull(name, "Dataset name cannot be null");

    return metadataProvider.exists(name);
  }

  @Override
  public Collection<String> list() {
    throw new UnsupportedOperationException();
  }

  public static class Builder {

    private Configuration configuration;

    public Builder configuration(Configuration configuration) {
      this.configuration = configuration;
      return this;
    }

    public HBaseDatasetRepository build() {
      HTablePool pool = new HTablePool(configuration, 10);
      HBaseAdmin admin;
      try {
        admin = new HBaseAdmin(configuration);
      } catch (MasterNotRunningException e) {
        throw new DatasetOperationException(
            "Problem creating HBaseDatasetRepository.", e);
      } catch (ZooKeeperConnectionException e) {
        throw new DatasetOperationException(
            "Problem creating HBaseDatasetRepository.", e);
      } catch (IOException e) {
        throw new DatasetIOException(
            "Problem creating HBaseDatasetRepository.", e);
      }
      return new HBaseDatasetRepository(admin, pool, getRepositoryUri(configuration));
    }

    private URI getRepositoryUri(Configuration conf) {
      return URI.create(String.format("repo:hbase:%s:%s",
          conf.get(HConstants.ZOOKEEPER_QUORUM),
          conf.get(HConstants.ZOOKEEPER_CLIENT_PORT)));
    }
  }
}
