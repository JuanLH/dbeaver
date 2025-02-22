/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2019 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.model.virtual;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.*;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.utils.CommonUtils;

import java.util.*;

/**
 * Virtual model utils
 */
public abstract class DBVUtils {

    static final Log log = Log.getLog(DBVUtils.class);

    // Entities for unmapped attributes (custom queries, pseudo attributes, etc)
    private static final Map<String, DBVEntity> orphanVirtualEntities = new HashMap<>();

    @Nullable
    public static DBVTransformSettings getTransformSettings(@NotNull DBDAttributeBinding binding, boolean create) {
        DBVEntity vEntity = getVirtualEntity(binding, create);
        if (vEntity != null) {
            DBVEntityAttribute vAttr = vEntity.getVirtualAttribute(binding, create);
            if (vAttr != null) {
                return getTransformSettings(vAttr, create);
            }
        }
        return null;
    }

    public static DBVEntity getVirtualEntity(@NotNull DBDAttributeBinding binding, boolean create) {
        DBSEntityAttribute entityAttribute = binding.getEntityAttribute();
        DBVEntity vEntity;
        if (entityAttribute != null) {
            vEntity = getVirtualEntity(entityAttribute.getParentObject(), create);
        } else {
            vEntity = getVirtualEntity(binding.getDataContainer(), create);

        }
        return vEntity;
    }

    @Nullable
    public static DBVEntity getVirtualEntity(@NotNull DBSEntity source, boolean create)
    {
        if (source instanceof DBVEntity) {
            return (DBVEntity) source;
        }
        return source.getDataSource().getContainer().getVirtualModel().findEntity(source, create);
    }

    public static DBVEntity getVirtualEntity(@NotNull DBSDataContainer dataContainer, boolean create) {
        if (dataContainer instanceof DBSEntity) {
            return getVirtualEntity((DBSEntity)dataContainer, create);
        }
        // Not an entity. Most likely a custom query. Use local cache for such attributes.
        // There shouldn't be too many such settings as they are defined by user manually
        // so we shouldn't eay too much memory for that
        String attrKey = DBUtils.getObjectFullId(dataContainer);
        synchronized (orphanVirtualEntities) {
            DBVEntity vEntity = orphanVirtualEntities.get(attrKey);
            if (vEntity == null && create) {
                vEntity = new DBVEntity(
                    dataContainer.getDataSource().getContainer().getVirtualModel(),
                    dataContainer.getName(),
                    "");
                orphanVirtualEntities.put(attrKey, vEntity);
            }
            return vEntity;
        }
    }

    @Nullable
    public static DBVTransformSettings getTransformSettings(@NotNull DBVEntityAttribute attribute, boolean create) {
        if (attribute.getTransformSettings() != null) {
            return attribute.getTransformSettings();
        } else if (create) {
            attribute.setTransformSettings(new DBVTransformSettings());
            return attribute.getTransformSettings();
        }
        for (DBVObject object = attribute.getParentObject(); object != null; object = object.getParentObject()) {
            if (object.getTransformSettings() != null) {
                return object.getTransformSettings();
            }
        }
        return null;
    }

    @NotNull
    public static Map<String, Object> getAttributeTransformersOptions(@NotNull DBDAttributeBinding binding) {
        Map<String, Object> options = null;
        final DBVTransformSettings transformSettings = getTransformSettings(binding, false);
        if (transformSettings != null) {
            options = transformSettings.getTransformOptions();
        }
        if (options != null) {
            return options;
        }
        return Collections.emptyMap();
    }

    @Nullable
    public static DBDAttributeTransformer[] findAttributeTransformers(@NotNull DBDAttributeBinding binding, @Nullable Boolean custom)
    {
        DBPDataSource dataSource = binding.getDataSource();
        DBPDataSourceContainer container = dataSource.getContainer();
        List<? extends DBDAttributeTransformerDescriptor> tdList =
            container.getPlatform().getValueHandlerRegistry().findTransformers(dataSource, binding.getAttribute(), custom);
        if (tdList == null || tdList.isEmpty()) {
            return null;
        }
        boolean filtered = false;
        final DBVTransformSettings transformSettings = getTransformSettings(binding, false);
        if (transformSettings != null) {
            filtered = transformSettings.filterTransformers(tdList);
        }

        if (!filtered) {
            // Leave only default transformers
            for (int i = 0; i < tdList.size();) {
                if (tdList.get(i).isCustom() || !tdList.get(i).isApplicableByDefault()) {
                    tdList.remove(i);
                } else {
                    i++;
                }
            }
        }
        if (tdList.isEmpty()) {
            return null;
        }
        DBDAttributeTransformer[] result = new DBDAttributeTransformer[tdList.size()];
        for (int i = 0; i < tdList.size(); i++) {
            result[i] = tdList.get(i).getInstance();
        }
        return result;
    }

    public static String getDictionaryDescriptionColumns(DBRProgressMonitor monitor, DBSEntityAttribute attribute) throws DBException {
        DBVEntity dictionary = DBVUtils.getVirtualEntity(attribute.getParentObject(), false);
        String descColumns = null;
        if (dictionary != null) {
            descColumns = dictionary.getDescriptionColumnNames();
        }
        if (descColumns == null) {
            descColumns = DBVEntity.getDefaultDescriptionColumn(monitor, attribute);
        }
        return descColumns;
    }

    @NotNull
    public static List<DBDLabelValuePair> readDictionaryRows(
        @NotNull DBCSession session,
        @NotNull DBSEntityAttribute valueAttribute,
        @NotNull DBDValueHandler valueHandler,
        @NotNull DBCResultSet dbResult) throws DBCException
    {
        List<DBDLabelValuePair> values = new ArrayList<>();
        List<DBCAttributeMetaData> metaColumns = dbResult.getMeta().getAttributes();
        List<DBDValueHandler> colHandlers = new ArrayList<>(metaColumns.size());
        for (DBCAttributeMetaData col : metaColumns) {
            colHandlers.add(DBUtils.findValueHandler(session, col));
        }
        boolean hasNulls = false;
        // Extract enumeration values and (optionally) their descriptions
        while (dbResult.nextRow()) {
            // Check monitor
            if (session.getProgressMonitor().isCanceled()) {
                break;
            }
            // Get value and description
            Object keyValue = valueHandler.fetchValueObject(session, dbResult, valueAttribute, 0);
            if (DBUtils.isNullValue(keyValue)) {
                if (hasNulls) {
                    continue;
                }
                hasNulls = true;
            }
            String keyLabel;
            if (metaColumns.size() > 1) {
                StringBuilder keyLabel2 = new StringBuilder();
                for (int i = 1; i < colHandlers.size(); i++) {
                    Object descValue = colHandlers.get(i).fetchValueObject(session, dbResult, metaColumns.get(i), i);
                    if (keyLabel2.length() > 0) {
                        keyLabel2.append(" ");
                    }
                    keyLabel2.append(colHandlers.get(i).getValueDisplayString(metaColumns.get(i), descValue, DBDDisplayFormat.NATIVE));
                }
                keyLabel = keyLabel2.toString();
            } else {
                keyLabel = valueHandler.getValueDisplayString(valueAttribute, keyValue, DBDDisplayFormat.NATIVE);
            }
            values.add(new DBDLabelValuePair(keyLabel, keyValue));
        }
        return values;
    }

    @NotNull
    public static List<DBSEntityConstraint> getAllConstraints(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) throws DBException {
        List<DBSEntityConstraint> result = new ArrayList<>();
        final Collection<? extends DBSEntityConstraint> realConstraints = entity.getConstraints(monitor);
        if (!CommonUtils.isEmpty(realConstraints)) {
            result.addAll(realConstraints);
        }
        DBVEntity vEntity = getVirtualEntity(entity, false);
        if (vEntity != null) {
            List<DBVEntityConstraint> vConstraints = vEntity.getConstraints();
            if (!CommonUtils.isEmpty(vConstraints)) {
                result.addAll(vConstraints);
            }
        }

        return result;
    }

    @NotNull
    public static List<DBSEntityAssociation> getAllAssociations(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) throws DBException {
        List<DBSEntityAssociation> result = new ArrayList<>();
        final Collection<? extends DBSEntityAssociation> realConstraints = entity.getAssociations(monitor);
        if (!CommonUtils.isEmpty(realConstraints)) {
            result.addAll(realConstraints);
        }
        DBVEntity vEntity = getVirtualEntity(entity, false);
        if (vEntity != null) {
            List<DBVEntityForeignKey> vFKs = vEntity.getForeignKeys();
            if (!CommonUtils.isEmpty(vFKs)) {
                result.addAll(vFKs);
            }
        }

        return result;
    }

    @NotNull
    public static List<DBSEntityAssociation> getAllReferences(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity onEntity) throws DBException {
        List<DBSEntityAssociation> result = new ArrayList<>();
        final Collection<? extends DBSEntityAssociation> realConstraints = onEntity.getReferences(monitor);
        if (!CommonUtils.isEmpty(realConstraints)) {
            result.addAll(realConstraints);
        }
/*
        DBVEntity vEntity = getVirtualEntity(entity, false);
        if (vEntity != null) {
            List<DBVEntityForeignKey> vFKs = vEntity.getForeignKeys();
            if (!CommonUtils.isEmpty(vFKs)) {
                result.addAll(vFKs);
            }
        }
*/

        return result;
    }

    @NotNull
    public static DBSEntity getRealEntity(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) throws DBException {
        if (entity instanceof DBVEntity) {
            DBSEntity realEntity = ((DBVEntity) entity).getRealEntity(monitor);
            if (realEntity == null) {
                throw new DBException("Can't locate real entity for " + DBUtils.getObjectFullId(entity));
            }
            return realEntity;
        }
        return entity;
    }

    @NotNull
    public static DBSEntity tryGetRealEntity(@NotNull DBSEntity entity) {
        if (entity instanceof DBVEntity) {
            try {
                return ((DBVEntity) entity).getRealEntity(new VoidProgressMonitor());
            } catch (DBException e) {
                log.error("Can't get real entity fro mvirtual entity", e);
            }
        }
        return entity;
    }

    public static DBVObject getVirtualObject(DBSObject source, boolean create) {
        if (source instanceof DBVObject) {
            return (DBVObject) source;
        }
        return source.getDataSource().getContainer().getVirtualModel().findObject(source, create);
    }
}
