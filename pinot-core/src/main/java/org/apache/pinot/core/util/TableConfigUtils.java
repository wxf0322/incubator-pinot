/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.util;

import com.google.common.base.Preconditions;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.pinot.common.tier.TierFactory;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.config.TagNameUtils;
import org.apache.pinot.core.data.function.FunctionEvaluator;
import org.apache.pinot.core.data.function.FunctionEvaluatorFactory;
import org.apache.pinot.spi.config.table.IngestionConfig;
import org.apache.pinot.spi.config.table.SegmentsValidationAndRetentionConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.config.table.TierConfig;
import org.apache.pinot.spi.config.table.ingestion.FilterConfig;
import org.apache.pinot.spi.config.table.ingestion.TransformConfig;
import org.apache.pinot.spi.utils.TimeUtils;


/**
 * Utils related to table config operations
 * FIXME: Merge this TableConfigUtils with the TableConfigUtils from pinot-common when merging of modules is done
 */
public final class TableConfigUtils {

  private TableConfigUtils() {

  }

  /**
   * Performs table config validations. Includes validations for the following:
   * 1. Validation config
   * 2. IngestionConfig
   * 3. TierConfigs
   */
  public static void validate(TableConfig tableConfig) {
    validateValidationConfig(tableConfig);
    validateIngestionConfig(tableConfig.getIngestionConfig());
    validateTierConfigList(tableConfig.getTierConfigsList());
  }

  /**
   * Validates the table name with the following rules:
   * <ul>
   *   <li>Table name shouldn't contain dot in it</li>
   * </ul>
   */
  public static void validateTableName(TableConfig tableConfig) {
    String tableName = tableConfig.getTableName();
    if (tableName.contains(".")) {
      throw new IllegalStateException("Table name: '" + tableName + "' containing '.' is not allowed");
    }
  }

  private static void validateValidationConfig(TableConfig tableConfig) {
    SegmentsValidationAndRetentionConfig validationConfig = tableConfig.getValidationConfig();
    if (validationConfig != null) {
      if (tableConfig.getTableType() == TableType.REALTIME && validationConfig.getTimeColumnName() == null) {
        throw new IllegalStateException("Must provide time column in real-time table config");
      }
      String peerSegmentDownloadScheme = validationConfig.getPeerSegmentDownloadScheme();
      if (peerSegmentDownloadScheme != null) {
        if (!CommonConstants.HTTP_PROTOCOL.equalsIgnoreCase(peerSegmentDownloadScheme)
            && !CommonConstants.HTTPS_PROTOCOL.equalsIgnoreCase(peerSegmentDownloadScheme)) {
          throw new IllegalStateException("Invalid value '" + peerSegmentDownloadScheme
              + "' for peerSegmentDownloadScheme. Must be one of http nor https");
        }
      }
    }
  }

  /**
   * Validates the following:
   * 1. validity of filter function
   * 2. checks for duplicate transform configs
   * 3. checks for null column name or transform function in transform config
   * 4. validity of transform function string
   * 5. checks for source fields used in destination columns
   */
  private static void validateIngestionConfig(@Nullable IngestionConfig ingestionConfig) {
    if (ingestionConfig != null) {
      FilterConfig filterConfig = ingestionConfig.getFilterConfig();
      if (filterConfig != null) {
        String filterFunction = filterConfig.getFilterFunction();
        if (filterFunction != null) {
          try {
            FunctionEvaluatorFactory.getExpressionEvaluator(filterFunction);
          } catch (Exception e) {
            throw new IllegalStateException("Invalid filter function " + filterFunction, e);
          }
        }
      }
      List<TransformConfig> transformConfigs = ingestionConfig.getTransformConfigs();
      if (transformConfigs != null) {
        Set<String> transformColumns = new HashSet<>();
        Set<String> argumentColumns = new HashSet<>();
        for (TransformConfig transformConfig : transformConfigs) {
          String columnName = transformConfig.getColumnName();
          String transformFunction = transformConfig.getTransformFunction();
          if (columnName == null || transformFunction == null) {
            throw new IllegalStateException(
                "columnName/transformFunction cannot be null in TransformConfig " + transformConfig);
          }
          if (!transformColumns.add(columnName)) {
            throw new IllegalStateException("Duplicate transform config found for column '" + columnName + "'");
          }
          FunctionEvaluator expressionEvaluator;
          try {
            expressionEvaluator = FunctionEvaluatorFactory.getExpressionEvaluator(transformFunction);
          } catch (Exception e) {
            throw new IllegalStateException(
                "Invalid transform function '" + transformFunction + "' for column '" + columnName + "'");
          }
          List<String> arguments = expressionEvaluator.getArguments();
          if (arguments.contains(columnName)) {
            throw new IllegalStateException(
                "Arguments of a transform function '" + arguments + "' cannot contain the destination column '"
                    + columnName + "'");
          }
          argumentColumns.addAll(arguments);
        }
        // TODO: remove this once we add support for derived columns/chained transform functions
        if (!Collections.disjoint(transformColumns, argumentColumns)) {
          throw new IllegalStateException(
              "Derived columns not supported yet. Cannot use a transform column as argument to another transform functions");
        }
      }
    }
  }

  /**
   * Validates the tier configs
   * Checks for the right segmentSelectorType and its required properties
   * Checks for the right storageType and its required properties
   */
  private static void validateTierConfigList(@Nullable List<TierConfig> tierConfigList) {
    if (tierConfigList == null) {
      return;
    }

    Set<String> tierNames = new HashSet<>();
    for (TierConfig tierConfig : tierConfigList) {
      String tierName = tierConfig.getName();
      Preconditions.checkState(!tierName.isEmpty(), "Tier name cannot be blank");
      Preconditions.checkState(tierNames.add(tierName), "Tier name: %s already exists in tier configs", tierName);

      String segmentSelectorType = tierConfig.getSegmentSelectorType();
      String segmentAge = tierConfig.getSegmentAge();
      if (segmentSelectorType.equalsIgnoreCase(TierFactory.TIME_SEGMENT_SELECTOR_TYPE)) {
        Preconditions
            .checkState(segmentAge != null, "Must provide 'segmentAge' for segmentSelectorType: %s in tier: %s",
                segmentSelectorType, tierName);
        Preconditions.checkState(TimeUtils.isPeriodValid(segmentAge),
            "segmentAge: %s must be a valid period string (eg. 30d, 24h) in tier: %s", segmentAge, tierName);
      } else {
        throw new IllegalStateException(
            "Unsupported segmentSelectorType: " + segmentSelectorType + " in tier: " + tierName);
      }

      String storageType = tierConfig.getStorageType();
      String serverTag = tierConfig.getServerTag();
      if (storageType.equalsIgnoreCase(TierFactory.PINOT_SERVER_STORAGE_TYPE)) {
        Preconditions
            .checkState(serverTag != null, "Must provide 'serverTag' for storageType: %s in tier: %s", storageType,
                tierName);
        Preconditions.checkState(TagNameUtils.isServerTag(serverTag),
            "serverTag: %s must have a valid server tag format (<tenantName>_OFFLINE or <tenantName>_REALTIME) in tier: %s",
            serverTag, tierName);
      } else {
        throw new IllegalStateException("Unsupported storageType: " + storageType + " in tier: " + tierName);
      }
    }
  }
}
