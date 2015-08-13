/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nway.spring.jdbc.bean;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.util.ClassUtils;

import com.nway.spring.classwork.DynamicBeanClassLoader;
import com.nway.spring.classwork.DynamicObjectException;
import com.nway.spring.jdbc.annotation.Column;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;

/**
 * <p>
 * <code>BeanProcessor</code> matches column names to bean property names and converts
 * <code>ResultSet</code> columns into objects for those bean properties. Subclasses should override
 * the methods in the processing chain to customize behavior.
 * </p>
 *
 * <p>
 * This class is thread-safe.
 * </p>
 *
 * 基于apache-dbutils，针对动态bean生成,修改了大部分方法的实现
 * <p>
 *
 * @since DbUtils 1.1
 */
class BeanProcessor {

	private static final BeanProcessor processor = new BeanProcessor();
	
	private static final Map<String, DbBeanFactory> DBBEANFACTORY_CACHE = new ConcurrentHashMap<String, DbBeanFactory>();

    /**
     * Special array value used by <code>mapColumnsToProperties</code> that indicates there is no
     * bean property that matches a column from a <code>ResultSet</code>.
     */
    private static final int PROPERTY_NOT_FOUND = -1;
    
    private static final int PROPERTY_TYPE_OTHER = 0;
    private static final int PROPERTY_TYPE_BOOLEAN = 1;
    private static final int PROPERTY_TYPE_BYTE = 2;
    private static final int PROPERTY_TYPE_SHORT = 3;
    private static final int PROPERTY_TYPE_INTEGER = 4;
    private static final int PROPERTY_TYPE_LONG = 5;
    private static final int PROPERTY_TYPE_FLOAT = 6;
    private static final int PROPERTY_TYPE_DOUBLE = 7;
    private static final int PROPERTY_TYPE_DATE = 8;
    
    private static final String DYNAMIC_BEAN_PACKAGE = "com.nway.spring.jdbc.bean.";
    
    private static final boolean HAS_ASM = ClassUtils.isPresent("org.objectweb.asm.ClassWriter", ClassUtils.getDefaultClassLoader());
	
	private static final boolean HAS_JAVASSIST = ClassUtils.isPresent("javassist.ClassPool", ClassUtils.getDefaultClassLoader());

    /**
     * Set a bean's primitive properties to these defaults when SQL NULL is returned. These are the
     * same as the defaults that ResultSet get* methods return in the event of a NULL column.
     */
    private static final Map<Class<?>, Object> primitiveDefaults = new HashMap<Class<?>, Object>();

    static {

        primitiveDefaults.put(Integer.TYPE, 0);
        primitiveDefaults.put(Short.TYPE, (short) 0);
        primitiveDefaults.put(Byte.TYPE, (byte) 0);
        primitiveDefaults.put(Float.TYPE, 0.0f);
        primitiveDefaults.put(Double.TYPE, 0.0d);
        primitiveDefaults.put(Long.TYPE, 0L);
        primitiveDefaults.put(Boolean.TYPE, Boolean.FALSE);
        primitiveDefaults.put(Character.TYPE, (char) 0);

    }

	private BeanProcessor() {
		
	}

	public static BeanProcessor getInstance() {

		return processor;
	}
    
    /**
     * Convert a <code>ResultSet</code> into a <code>List</code> of JavaBeans. This implementation
     * uses reflection and <code>BeanInfo</code> classes to match column names to bean property
     * names. Properties are matched to columns based on several factors: <br/>
     * <ol>
     * <li>
     * The class has a writable property with the same name as a column. The name comparison is case
     * insensitive.</li>
     *
     * <li>
     * The column type can be converted to the property's set method parameter type with a
     * ResultSet.get* method. If the conversion fails (ie. the property was an int and the column
     * was a Timestamp) an SQLException is thrown.</li>
     * </ol>
     *
     * <p>
     * Primitive bean properties are set to their defaults when SQL NULL is returned from the
     * <code>ResultSet</code>. Numeric fields are set to 0 and booleans are set to false. Object
     * bean properties are set to <code>null</code> when SQL NULL is returned. This is the same
     * behavior as the <code>ResultSet</code> get* methods.
     * </p>
     *
     * @param <T> The type of bean to create
     * @param rs ResultSet that supplies the bean data
     * @param type Class from which to create the bean instance
     * @throws SQLException if a database access error occurs
     * @return the newly created List of beans
     */
    public <T> List<T> toBeanList(ResultSet rs, Class<T> type, String querying) throws SQLException {

        if (!rs.next()) {
        	
            return Collections.emptyList();
        }
        
        final List<T> results = new ArrayList<T>();

        String cacheKey = makeCacheKey(rs, querying, type.getName());
		
        do {
            results.add(toBean(rs, type, cacheKey, false));
        } while (rs.next());

        return results;
    }
    
    /**
     * 生成动态bean，并将数据和bean合并
     * <p>
     *
     * <b>默认优先使用ASM生成动态bean，如果需要以javassist方式生成动态bean，请将本实现切换为{@link
     * this#createBeanByJavassist(ResultSet, Class, String)}</b>
     *
     * @param <T>
     * @param rs {@link ResultSet}
     * @param type bean类型
     * @param querying sql查询信息
     * @return 包含数据的bean
     *
     * @throws SQLException
     */
    public <T> T toBean(ResultSet rs, Class<T> type, String querying) throws SQLException {
    	
    	return toBean(rs, type, querying, true);
    }

	private <T> T toBean(ResultSet rs, Class<T> type, String querying, boolean makeKey) throws SQLException {

		String cacheKey = null;

		if (makeKey) {

			cacheKey = makeCacheKey(rs, querying, type.getName());
		} else {

			cacheKey = querying;
		}

		/*
		 * 同步可以提高单次响应效率，但会降低系统整体吞吐量。
		 * 如果不做线程同步，只有当存在某一查询一开始就大量并发访问时，才会在前几次查询中重复定义动态相同的DbBeanFactory
		 * 以type对象作为同步锁，降低线程同步对系统整体吞吐量的影响
		 */
//		synchronized (type) {

			if (HAS_ASM) {

				return createBeanByASM(rs, type, cacheKey);
			} 
			else if (HAS_JAVASSIST) {

				return createBeanByJavassist(rs, type, cacheKey);
			} 
			else {

				throw new RuntimeException("");
			}

//		}

	}

    private <T> T createBeanByJavassist(ResultSet rs, Class<T> mappedClass, String key)
            throws SQLException {

        DbBeanFactory dynamicRse = DBBEANFACTORY_CACHE.get(key);

        // 如果缓存中有则直接返回
        if (dynamicRse != null) {

            return dynamicRse.createBean(rs, mappedClass);
        }

        T bean = this.newInstance(mappedClass);

        ResultSetMetaData rsmd = rs.getMetaData();

        PropertyDescriptor[] props = BeanUtils.getPropertyDescriptors(mappedClass);

        int[] columnToProperty = this.mapColumnsToProperties(rsmd, props);

        StringBuilder handlerScript = new StringBuilder();

        handlerScript.append("{").append(mappedClass.getName()).append(" bean = new ")
                .append(mappedClass.getName()).append("();\n");

        PropertyDescriptor desc = null;
        for (int i = 1; i < columnToProperty.length; i++) {

            if (columnToProperty[i] == PROPERTY_NOT_FOUND) {
                continue;
            }

            desc = props[columnToProperty[i]];
            Class<?> propType = desc.getPropertyType();

            Object value = processColumn(rs, i, propType, desc.getWriteMethod().getName(),
                    handlerScript);

            this.callSetter(bean, desc, value);

        }

        handlerScript.append("return bean;");

        handlerScript.append("}");

        try {

            ClassPool classPool = ClassPoolCreator.getClassPool();

            CtClass ctHandler = classPool.makeClass(getProcessorName(mappedClass));
            ctHandler.setSuperclass(classPool.get("com.nway.spring.jdbc.bean.DbBeanFactory"));
            
            CtMethod mapRow = CtNewMethod
                    .make("public Object createBean(java.sql.ResultSet rs, Class type) throws java.sql.SQLException{return null;}",
                            ctHandler);

            mapRow.setGenericSignature("<T:Ljava/lang/Object;>(Ljava/sql/ResultSet;Ljava/lang/Class<TT;>;)TT;");
            
            mapRow.setBody(handlerScript.toString());

            ctHandler.addMethod(mapRow);
			
            DBBEANFACTORY_CACHE.put(key, (DbBeanFactory) ctHandler.toClass().newInstance());

        } catch (Exception e) {

            throw new DynamicObjectException("使用javassist创建 [ " + mappedClass.getName() + " ] 失败", e);
        }

        return bean;
    }

    /**
     * Creates a new object and initializes its fields from the ResultSet.
     *
     * @param <T> The type of bean to create
     * @param rs The result set.
     * @param type The bean type (the return type of the object).
     * @param props The property descriptors.
     * @param columnToProperty The column indices in the result set.
     * @return An initialized object.
     * @throws SQLException if a database error occurs.
     */
    private <T> T createBeanByASM(ResultSet rs, Class<T> mappedClass, String key) throws SQLException {

        DbBeanFactory dynamicRse = DBBEANFACTORY_CACHE.get(key);
        
        // 如果缓存中有则直接返回
        if (dynamicRse != null) {

            return dynamicRse.createBean(rs, mappedClass);
        }

        T bean = this.newInstance(mappedClass);

        ResultSetMetaData rsmd = rs.getMetaData();

        MethodVisitor mv = null;

        final ClassWriter cw = new ClassWriter(0);

        PropertyDescriptor[] props = BeanUtils.getPropertyDescriptors(mappedClass);

        int[] columnToProperty = this.mapColumnsToProperties(rsmd, props);

        String beanName = mappedClass.getName().replace('.', '/');
        
        String processorName = getProcessorName(mappedClass);
        
        String internalProcessorName = processorName.replace('.', '/');

        Object[] labelArr = prepScript(cw, mv, internalProcessorName, beanName);

        mv = (MethodVisitor) labelArr[1];

        Label firstLabel = null;
        PropertyDescriptor desc = null;

        for (int i = 1; i < columnToProperty.length; i++) {

            if (columnToProperty[i] == PROPERTY_NOT_FOUND) {
                continue;
            }

            Object value = null;
            desc = props[columnToProperty[i]];
            Class<?> propType = desc.getPropertyType();

            if (null == firstLabel) {
            	
                firstLabel = firstLabel(mv, beanName, 12);
            } 
            else {
            	
                visitLabel(mv, 11 + i);
            }

            // 生成 rs.getXXX
            value = processColumn(rs, i, propType, desc.getWriteMethod().getName(), internalProcessorName, beanName, cw, mv);

            this.callSetter(bean, desc, value);
        }

		if (firstLabel != null) {
        	
	        endScript(mv, (Label) labelArr[0], firstLabel, 12 + columnToProperty.length, internalProcessorName, beanName);
	
	        cw.visitEnd();
	
	        try {
	
	            DynamicBeanClassLoader beanClassLoader = new DynamicBeanClassLoader(ClassUtils.getDefaultClassLoader());
	
				Class<?> processor = beanClassLoader.defineClass(processorName, cw.toByteArray());
	
				DBBEANFACTORY_CACHE.put(key, (DbBeanFactory) processor.newInstance());
	
	        } catch (Exception e) {
	
	            throw new DynamicObjectException("使用ASM创建 [ " + processorName + " ] 失败", e);
	        }
        }

        return bean;
    }

    /**
     * Calls the setter method on the target object for the given property. If no setter method
     * exists for the property, this method does nothing.
     *
     * @param target The object to set the property on.
     * @param prop The property to set.
     * @param value The value to pass into the setter.
     * @throws SQLException if an error occurs setting the property.
     */
    private void callSetter(Object target, PropertyDescriptor prop, Object value)
            throws SQLException {

        Method setter = prop.getWriteMethod();

        if (setter == null) {
        	
            return;
        }

        try {

            // Don't call setter if the value object isn't the right type
            // if (this.isCompatibleType(value, params[0])) {
            setter.invoke(target, new Object[]{value});
            
        } 
        catch (Exception e) {

            throw new SQLException("Cannot set " + prop.getName() + ": " + e.toString());
        }
    }

    /**
     * Factory method that returns a new instance of the given Class. This is called at the start of
     * the bean creation process and may be overridden to provide custom behavior like returning a
     * cached bean instance.
     *
     * @param <T> The type of object to create
     * @param c The Class to create an object from.
     * @return A newly created object of the Class.
     * @throws SQLException if creation failed.
     */
    private <T> T newInstance(Class<T> c) throws SQLException {

        try {

            return c.newInstance();
        } 
        catch (InstantiationException e) {

            throw new SQLException("Cannot create " + c.getName() + ": " + e.toString());
        } 
        catch (IllegalAccessException e) {

            throw new SQLException("Cannot create " + c.getName() + ": " + e.toString());
        }
    }

    /**
     * The positions in the returned array represent column numbers. The values stored at each
     * position represent the index in the <code>PropertyDescriptor[]</code> for the bean property
     * that matches the column name. If no bean property was found for a column, the position is set
     * to <code>PROPERTY_NOT_FOUND</code>.
     *
     * @param rsmd The <code>ResultSetMetaData</code> containing column information.
     *
     * @param props The bean property descriptors.
     *
     * @throws SQLException if a database access error occurs
     *
     * @return An int[] with column index to property index mappings. The 0th element is meaningless
     * because JDBC column indexing starts at 1.
     */
    private int[] mapColumnsToProperties(ResultSetMetaData rsmd, PropertyDescriptor[] props)
            throws SQLException {

        int cols = rsmd.getColumnCount();
        int[] columnToProperty = new int[cols + 1];

        Arrays.fill(columnToProperty, PROPERTY_NOT_FOUND);

        for (int col = 1; col <= cols; col++) {

            String columnName = rsmd.getColumnLabel(col);

            for (int i = 0; i < props.length; i++) {

                Column columnAnnotation = props[i].getReadMethod().getAnnotation(Column.class);

                if (columnAnnotation == null) {

                    //去除列名里的下划线'_'
                    if (columnName.replace("_", "").equalsIgnoreCase(props[i].getName())) {
                    	
                        columnToProperty[col] = i;
                        break;
                    }
                }
                else if (columnName.equalsIgnoreCase(columnAnnotation.value())
                        || columnName.equalsIgnoreCase(columnAnnotation.name())) {
                	
                    columnToProperty[col] = i;
                    break;
                }

            }
        }

        return columnToProperty;
    }

	private Object processColumn(ResultSet rs, int index, Class<?> propType, String writer, String processorName,
			String beanName, ClassWriter cw, MethodVisitor mv) throws SQLException {

		if (propType.equals(String.class))
		{
			visitMethod(mv, index, beanName, "Ljava/lang/String;", "getString", writer);
			return rs.getString(index);
		}
		else if (propType.equals(Integer.TYPE))
		{
			visitMethod(mv, index, beanName, "I", "getInt", writer);
			return rs.getInt(index);
		}
		else if (propType.equals(Integer.class))
		{
			visitMethodWrap(mv, index,beanName, PROPERTY_TYPE_INTEGER, writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Integer.class);
		}
		else if (propType.equals(Long.TYPE))
		{
			visitMethod(mv, index, beanName, "J", "getLong", writer);
			return rs.getLong(index);
		}
		else if (propType.equals(Long.class))
		{
			visitMethodWrap(mv, index, beanName, PROPERTY_TYPE_LONG, writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Long.class);
		}
		else if (propType.equals(java.sql.Date.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Date;", "getDate", writer);
			return rs.getDate(index);
		}
		else if (propType.equals(java.util.Date.class))
		{
			visitMethodCast(mv, index, beanName, PROPERTY_TYPE_DATE, "java/util/Date", writer);
			return rs.getTimestamp(index);
		}
		else if (propType.equals(Timestamp.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Timestamp;", "getTimestamp", writer);
			return rs.getTimestamp(index);
		}
		else if (propType.equals(Time.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Time;", "getTime", writer);
			return rs.getTime(index);
		}
		else if (propType.equals(Double.TYPE))
		{
			visitMethod(mv, index, beanName, "D", "getDouble", writer);
			return rs.getDouble(index);
		}
		else if (propType.equals(Double.class))
		{
			visitMethodWrap(mv, index, beanName, PROPERTY_TYPE_DOUBLE, writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Double.class);
		}
		else if (propType.equals(Float.TYPE))
		{
			visitMethod(mv, index, beanName, "F", "getFloat", writer);
			return rs.getFloat(index);
		}
		else if (propType.equals(Float.class))
		{
			visitMethodWrap(mv, index,  beanName, PROPERTY_TYPE_FLOAT,  writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Float.class);
		}
		else if (propType.equals(Time.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Time;", "getTime", writer);
			return rs.getTime(index);
		}
		else if (propType.equals(Boolean.TYPE))
		{
			visitMethod(mv, index, beanName, "Z", "getBoolean", writer);
			return rs.getBoolean(index);
		}
		else if (propType.equals(Boolean.class))
		{
			visitMethodWrap(mv, index, beanName, PROPERTY_TYPE_BOOLEAN,  writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Boolean.class);
		}
		else if (propType.equals(Clob.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Clob;", "getClob", writer);
			return rs.getClob(index);
		}
		else if (propType.equals(Blob.class))
		{
			visitMethod(mv, index, beanName, "Ljava/sql/Blob;", "getBlob", writer);
			return rs.getBlob(index);
		}
		else if (propType.equals(byte[].class))
		{
			visitMethod(mv, index, beanName, "[B", "getBytes", writer);
			return rs.getBytes(index);
		}
		else if (propType.equals(Short.TYPE))
		{
			visitMethod(mv, index, beanName, "S", "getShort", writer);
			return rs.getShort(index);
		}
		else if (propType.equals(Short.class))
		{
			visitMethodWrap(mv, index, beanName, PROPERTY_TYPE_SHORT, writer, processorName);
			return JdbcUtils.getResultSetValue(rs, index, Short.class);
		}
		else if (propType.equals(Byte.TYPE))
		{
			visitMethod(mv, index, beanName, "B", "getByte", writer);
			return rs.getByte(index);
		}
		else if (propType.equals(Byte.class))
		{
			visitMethodWrap(mv, index, beanName, PROPERTY_TYPE_BYTE, writer, processorName);
			return rs.getByte(index);
		}
		else
		{
			visitMethodCast(mv, index, beanName, PROPERTY_TYPE_OTHER, propType.getName().replace('.', '/'), writer);
			return rs.getObject(index);
		}
    }

	private Object processColumn(ResultSet rs, int index, Class<?> propType, String writer,
			StringBuilder handler) throws SQLException
	{
		if (propType.equals(String.class))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getString(").append(index).append("));");
			return rs.getString(index);
		}
		else if (propType.equals(Integer.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getInt(").append(index).append("));");
			return rs.getInt(index);
		}
		else if (propType.equals(Integer.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("integerValue($1.getInt(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Integer.class);
		}
		else if (propType.equals(Long.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getLong(").append(index).append("));");
			return rs.getLong(index);
		}
		else if (propType.equals(Long.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("longValue($1.getLong(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Long.class);
		}
		else if (propType.equals(java.sql.Date.class))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getDate(").append(index).append("));");
			return rs.getDate(index);
		}
		else if (propType.equals(java.util.Date.class) || propType.equals(Timestamp.class))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getTimestamp(").append(index).append("));");
			return rs.getTimestamp(index);
		}
		else if (propType.equals(Double.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getDouble(").append(index).append("));");
			return rs.getDouble(index);
		}
		else if (propType.equals(Double.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("doubleValue($1.getDouble(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Double.class);
		}
		else if (propType.equals(Float.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getFloat(").append(index).append("));");
			return rs.getFloat(index);
		}
		else if (propType.equals(Float.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("floatValue($1.getFloat(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Float.class);
		}
		else if (propType.equals(Time.class))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getTime(").append(index).append("));");
			return rs.getTime(index);
		}
		else if (propType.equals(Boolean.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getBoolean(").append(index).append("));");
			return rs.getBoolean(index);
		}
		else if (propType.equals(Boolean.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("booleanValue($1.getBoolean(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Boolean.class);
		}
		else if (propType.equals(byte[].class))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getBytes(").append(index).append("));");
			return rs.getBytes(index);
		}
		else if (BigDecimal.class.equals(propType))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getBigDecimal(").append(index).append("));");
			return rs.getBigDecimal(index);
		}
		else if (Blob.class.equals(propType))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getBlob(").append(index).append("));");
			return rs.getBlob(index);
		}
		else if (Clob.class.equals(propType))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getClob(").append(index).append("));");
			return rs.getClob(index);
		}
		else if (propType.equals(Short.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getShort(").append(index).append("));");
			return rs.getShort(index);
		}
		else if (propType.equals(Short.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("shortValue($1.getShort(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Short.class);
		}
		else if (propType.equals(Byte.TYPE))
		{
			handler.append("bean.").append(writer).append("(").append("$1.getByte(").append(index).append("));");
			return rs.getByte(index);
		}
		else if (propType.equals(Byte.class))
		{
			handler.append("bean.").append(writer).append("(")
					.append("byteValue($1.getByte(").append(index)
					.append("),$1.wasNull()));");
			return JdbcUtils.getResultSetValue(rs, index, Byte.class);
		}
		else
		{
			handler.append("bean.").append(writer).append("(").append("(")
					.append(propType.getName()).append(")").append("$1.getObject(").append(index)
					.append("));");
			return rs.getObject(index);
		}
	}

    /**
     *
     *
     * @param mv ${@link MethodVisitor}
     * @param index rs.getDate(index)
     * @param beanSignature com/nway/commons/dbutils/test/User
     * @param rsTypeDesc Ljava/sql/Date;
     * @param beanTypeDesc Ljava/util/Date;
     * @param rsMethod getDate
     * @param writeMethod setDate
     */
	private void visitMethod(MethodVisitor mv, int index, String beanSignature,
			String beanTypeDesc, String rsMethod, String writeMethod) {

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        
        visitInsn(mv, index);
		
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", rsMethod, "(I)" + beanTypeDesc, true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(" + beanTypeDesc + ")V", false);
    }
	
	private void visitMethodCast(MethodVisitor mv, int index, String beanSignature,int beanType,
			String beanTypeDesc, String writeMethod) {
		
		mv.visitVarInsn(Opcodes.ALOAD, 3);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		
		visitInsn(mv, index);
		
		switch (beanType) {
			case PROPERTY_TYPE_DATE:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getTimestamp", "(I)Ljava/sql/Timestamp;", true);
				break;
			default:
				mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/springframework/jdbc/support/JdbcUtils", "getResultSetValue", "(Ljava/sql/ResultSet;I)Ljava/lang/Object;", false);
				mv.visitTypeInsn(Opcodes.CHECKCAST, beanTypeDesc);
				break;
		}
		
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(L" + beanTypeDesc + ";)V", false);
	}
	
	private void visitMethodWrap(MethodVisitor mv, int index, String beanSignature, int beanType, String writeMethod, String processorName) {
		
		mv.visitVarInsn(Opcodes.ALOAD, 3);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		
		visitInsn(mv, index);
		
		switch (beanType) {
			case PROPERTY_TYPE_INTEGER:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getInt", "(I)I", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "integerValue", "(IZ)Ljava/lang/Integer;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Integer;)V", false);
				break;
			case PROPERTY_TYPE_LONG:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getLong", "(I)J", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "longValue", "(JZ)Ljava/lang/Long;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Long;)V", false);
				break;
			case PROPERTY_TYPE_BOOLEAN:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getBoolean", "(I)Z", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "booleanValue", "(ZZ)Ljava/lang/Boolean;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Boolean;)V", false);
				break;
			case PROPERTY_TYPE_FLOAT:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getFloat", "(I)F", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "floatValue", "(FZ)Ljava/lang/Float;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Float;)V", false);
				break;
			case PROPERTY_TYPE_DOUBLE:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getDouble", "(I)D", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "doubleValue", "(DZ)Ljava/lang/Double;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Double;)V", false);
				break;
			case PROPERTY_TYPE_BYTE:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getByte", "(I)B", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "byteValue", "(BZ)Ljava/lang/Byte;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Byte;)V", false);
				break;
			case PROPERTY_TYPE_SHORT:
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "getShort", "(I)S", true);
				mv.visitVarInsn(Opcodes.ALOAD, 1);
				mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/sql/ResultSet", "wasNull", "()Z", true);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, processorName, "shortValue", "(SZ)Ljava/lang/Short;", false);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, beanSignature, writeMethod, "(Ljava/lang/Short;)V", false);
				break;
		}
	}

	private void visitInsn(MethodVisitor mv, int index) {

		switch (index) {
		case 1:
			mv.visitInsn(Opcodes.ICONST_1);
			break;
		case 2:
			mv.visitInsn(Opcodes.ICONST_2);
			break;
		case 3:
			mv.visitInsn(Opcodes.ICONST_3);
			break;
		case 4:
			mv.visitInsn(Opcodes.ICONST_4);
			break;
		case 5:
			mv.visitInsn(Opcodes.ICONST_5);
			break;
		default:
			mv.visitIntInsn(Opcodes.BIPUSH, index);
		}
	}

    /**
     *
     *
     * @param mv
     * @param beanName com/nway/commons/dbutils/test/User
     * @param lineNumber
     * @return
     */
    private Label firstLabel(MethodVisitor mv, String beanName, int lineNumber) {

        Label label = new Label();
        mv.visitLabel(label);
        mv.visitLineNumber(lineNumber, label);

        return label;
    }

    private void visitLabel(MethodVisitor mv, int lineNumber) {

        Label label = new Label();
        mv.visitLabel(label);
        mv.visitLineNumber(lineNumber, label);
    }

    /**
     *
     * 动态类脚本开始
     *
     * @param cw
     * @param mv
     * @param processorName com/nway/commons/dbutils/DynamicBeanProcessorImpl
     * @param beanName com/nway/commons/dbutils/test/User
     * @return [0]:bean标签；[1]：createBean方法句柄
     */
    private Object[] prepScript(ClassWriter cw, MethodVisitor mv, String processorName,
            String beanName) {

        Object[] lab = new Object[2];

		cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, processorName, null,
				"com/nway/spring/jdbc/bean/DbBeanFactory", null);

		cw.visitSource(processorName.substring(processorName.lastIndexOf('/') + 1) + ".java", null);
		
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(6, l0);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/nway/spring/jdbc/bean/DbBeanFactory", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "L" + processorName + ";", null, l0, l1, 0);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "createBean",
                    "(Ljava/sql/ResultSet;Ljava/lang/Class;)Ljava/lang/Object;",
                    "<T:Ljava/lang/Object;>(Ljava/sql/ResultSet;Ljava/lang/Class<TT;>;)TT;",
                    new String[]{"java/sql/SQLException"});
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(10, l0);
            mv.visitTypeInsn(Opcodes.NEW, beanName);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, beanName, "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, 3);

            lab[0] = l0;
            lab[1] = mv;
        }

        return lab;
    }

    /**
     *
     * 动态类脚本收尾
     *
     * @param mv MethodVisitor
     * @param processorName com/nway/commons/dbutils/DynamicBeanProcessorImpl
     * @param beanName com/nway/commons/dbutils/test/User
     */
    private void endScript(MethodVisitor mv, Label processorLabel, Label beanStart, int lineNumber,
            String processorName, String beanName) {

        Label l10 = new Label();
        mv.visitLabel(l10);
        mv.visitLineNumber(lineNumber, l10);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ARETURN);
        Label l11 = new Label();
        mv.visitLabel(l11);
        mv.visitLocalVariable("this", "L" + processorName + ";", null, processorLabel, l11, 0);
        mv.visitLocalVariable("rs", "Ljava/sql/ResultSet;", null, processorLabel, l11, 1);
        mv.visitLocalVariable("type", "Ljava/lang/Class;", "Ljava/lang/Class<TT;>;", processorLabel, l11, 2);
        mv.visitLocalVariable("bean", "L" + beanName + ";", null, beanStart, l11, 3);
        mv.visitMaxs(5, 4);
        mv.visitEnd();
    }

	/**
	 * 
	 * 
	 * @param rsMetaData
	 * @param querying
	 *            {@link com.nway.spring.jdbc.SqlExecutor#extractQueryingColumn}
	 * @param className
	 * @return
	 * @throws SQLException
	 */
	private String makeCacheKey(ResultSet rs, String querying, String className) throws SQLException {
 	
    	String cacheKey = null;
    	
    	if (querying == null) {

			cacheKey = getColunmNames(rs.getMetaData()) + className;
		} 
		else {
			
			cacheKey = querying + className;
		}
    	
    	return cacheKey;
    }
    
    /**
     * 获取查询的所有列名，而非列的别名
     *
     * @param rsmd {@link java.sql.ResultSetMetaData}
     * @return 大写的所有列名（没有间隔符）
     * @throws SQLException
     */
    private String getColunmNames(ResultSetMetaData rsmd) throws SQLException {

        StringBuilder columnNames = new StringBuilder(64);

        for (int i = 1; i <= rsmd.getColumnCount(); i++) {

            columnNames.append(rsmd.getColumnLabel(i));
        }

        return columnNames.toString();
    }

	private String getProcessorName(Class<?> type) {

		return DYNAMIC_BEAN_PACKAGE + type.getSimpleName() + System.nanoTime();
	}
}
