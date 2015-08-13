package com.nway.spring.jdbc;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.hibernate.Hibernate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 初始化测试表 可以执行initTable()方法
 * 
 * @author zdtjss@163.com
 *
 */
public class SqlExecutorTest extends BaseTest
{
	@Autowired
	private SqlExecutor sqlExecutor;
	
	@Test
	public void testQueryForBean()
	{
		String sql = "select * from t_nway where rownum = 1";
		
		ExampleEntity usr = sqlExecutor.queryForBean(sql, ExampleEntity.class);

		System.out.println(usr.toString());
	}

	@Test
	public void testQueryForBeanList() {
		
		String sql = "select a.*,'from' from( select * from t_nway limit 2) a";

		List<ExampleEntity> users = sqlExecutor.queryForBeanList(sql, ExampleEntity.class);

		System.out.println(users);
	}

	@Test
	public void testQueryBeanPagination()
	{
		String sql = "select * from t_nway order by c_p_int";

		Pagination<ExampleEntity> users = sqlExecutor.queryForBeanPagination(sql, null, 1, 5, ExampleEntity.class);

		System.out.println(users);
	}

	@Test
	public void testQueryMapListPagination()
	{
		String sql = "select * from t_nway order by c_int";

		Pagination<Map<String, Object>> users = sqlExecutor.queryForMapListPagination(sql,null, 1, 3);

		System.out.println(users);
	}

	@Test
	public void regex() {

		String input = "a order by abc1,a.ab2  ";

		Pattern ORDER_BY_PATTERN = Pattern
				.compile(".+\\p{Blank}+ORDER\\p{Blank}+BY[\\,\\p{Blank}\\w\\.]+");

		System.out.println(ORDER_BY_PATTERN.matcher(input.toUpperCase()).matches());
	}
	
	@Test
	public void countSql() {
		
		StringBuilder countSql = new StringBuilder();
		
		//countSql.append("select DISTINCT aaa,bbb from t_nway".toUpperCase());
		countSql.append("select top 50 PERCENT * from t_nway".toUpperCase());
		
		int firstFromIndex = countSql.indexOf(" FROM ");
		
		String selectedColumns = countSql.substring(0, firstFromIndex + 1);
		
		if (selectedColumns.indexOf(" DISTINCT ") == -1
				&& !selectedColumns.matches(".+TOP\\p{Blank}+\\d+\\p{Blank}+.+")) {

			countSql = countSql.delete(0, firstFromIndex).insert(0, "SELECT COUNT(1)");
		} else {

			countSql.insert(0, "SELECT COUNT(1) FROM (").append(')');
		}
		
		System.out.println(countSql);
	}

	@Test
	public void testJson()
	{
		String sql = "select * from t_nway where rownum = 1";
		
		String json = sqlExecutor.queryForJson(sql, ExampleEntity.class);
		
		System.out.println(json);
	}
	
	@Test
	public void testJsonList() {
		
		String sql = "select * from t_nway where rownum < 10";

		String json = sqlExecutor.queryForJsonList(sql, ExampleEntity.class);

		System.out.println(json);
	}
	
	@Test
	public void testJsonPagination()
	{
		String sql = "select * from t_nway order by c_p_int";

		String json = sqlExecutor.queryForJsonPagination(sql, null, 1, 5, ExampleEntity.class);

		System.out.println(json);
	}
	
	@Test
	public void initTable() throws SQLException {

		SessionFactory sessionFactory = null;
		
		try {

			Configuration cfg = new Configuration().configure();

			// Create the SessionFactory from hibernate.cfg.xml
			sessionFactory = cfg.buildSessionFactory(
					new StandardServiceRegistryBuilder().applySettings(cfg.getProperties()).build());
		} catch (Throwable ex) {
			// Make sure you log the exception, as it might be swallowed
			System.err.println("Initial SessionFactory creation failed." + ex);
			throw new ExceptionInInitializerError(ex);
		}
		
		Session session = sessionFactory.openSession();
		
		Transaction transaction = session.beginTransaction();
		
		for (int i = 0; i < 100; i++) {
			
			ExampleEntity example = new ExampleEntity();

			DecimalFormat numberFormat = new DecimalFormat("0000000000.00");

			example.setId((int) (Math.random() * 10) % 2);

			example.setpBoolean(1 == ((Math.random() * 10) % 2));

			example.setpByte((byte) (Math.random() * 100));

			example.setpShort((short) (Math.random() * 100));

			example.setpInt((int) (Math.random() * 1000000));
			
			example.setpLong((long) (Math.random() * 10000000));
			
			example.setpFloat(Float.parseFloat(numberFormat.format((Math.random() * 1000000000))));
			
			example.setpDouble(Double.parseDouble(numberFormat.format((Math.random() * 1000000000))));
			
			example.setpByteArr("nway-jdbc".getBytes());
			
			example.setwBoolean(Boolean.valueOf(1 == ((Math.random() * 10) % 2)));
			
			example.setwByte(Byte.valueOf((byte) (Math.random() * 100)));
			
			example.setwShort(Short.valueOf(((short) (Math.random() * 100))));
			
			example.setwInt(Integer.valueOf(((int) (Math.random() * 100000))));
			
			example.setwLong(Long.valueOf((long) (Math.random() * 10000000)));
			
			example.setwFloat(Float.valueOf(numberFormat.format((Math.random() * 1000000000))));
			
			example.setwDouble(Double.valueOf(numberFormat.format((Math.random() * 1000000000))));
			
			example.setString(UUID.randomUUID().toString());
			
			example.setUtilDate(new Date());
			
			example.setSqlDate(new java.sql.Date(System.currentTimeMillis()));
			
			example.setTimestamp(new Timestamp(System.currentTimeMillis()));
			
			example.setClob(Hibernate.getLobCreator(session).createClob("nway"));
			
			example.setBlob(Hibernate.getLobCreator(session).createBlob("nway".getBytes()));
			
//			example.setInputStream(Hibernate.getLobCreator(session).createBlob("nway".getBytes()).getBinaryStream());
		
			session.save(example);
		}
		
		transaction.commit();
	}
	
}
