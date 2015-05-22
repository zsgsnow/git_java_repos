package sms_mid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class TransferMail {
	 /**
     * 功能：读取邮件转发至短信中间件mysql数据库
     * Author: Zsg
     * 步骤：
     * 1：先获得数据库和邮件服务器的配置信息
     * 2：根据配置信息连接mysql数据库
     * 3：根据配置信息连接邮件服务器
     * 4：读取邮件转发至数据库
     */
 static Logger logger = Logger.getLogger(TransferMail.class);
 static String adminPhone = null;
 static PreparedStatement  preStatement = null;
 static Store store = null;
 static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm");//设置输出的时间格式
 
 public static void main(String[] args) {
		//1. 获得数据库和邮件服务器的配置信息
		Properties props = getConfig();
		if (props == null) return; 
		adminPhone=props.getProperty("Admin.phone");   //短信平台管理员手机号
		//2. 根据配置信息连接mysql数据库
	     connectToMysql(props);
	     if (preStatement == null) return;
		  
		//3. 根据配置信息连接邮件服务器
	     connectToMail(props);
	     if (store == null) return;
		
		//4. 读取邮件转发至数据库
	    InputStreamReader read = readUsersInfo();
		if (read == null) return;
        BufferedReader bufferedReader = new BufferedReader(read); 
        String lineTxt =null;
		String userName =null;
		String userPwd =null;
		Boolean b_content = false; //判断邮件是包含文本信息 
		try {
			while((lineTxt = bufferedReader.readLine()) != null){
				if(lineTxt.trim().startsWith("#")) continue;
				String[] user_ary = lineTxt.trim().split("\\s+");//空格分割一行用户信息
				if(user_ary.length<3){
					logger.error("失败：查找用户资料  " + ". 应该形如: 用户名	密码	手机号");
					bufferedReader.close();
					return;
				}
				userName = user_ary[0];
				userPwd =  user_ary[1];
				if (isOkToMailAccount(userName,userPwd) == false) continue; //若账号不可连接，继续下一个账号
				Folder folder = store.getFolder("INBOX");
				folder.open(Folder.READ_WRITE);
				Message message[] = folder.getMessages();
				logger.info("成功：连接邮箱账号" + userName + "，读取邮件数量：" + message.length);
				int numOfSuccess =0; //记录每个账号写入数据库的邮件数量 
				int numOfNoText =0;  //记录每个账号不含文本信息的邮件数量
				int numOfFailure =0; //记录每个账号写入数据库失败的邮件数量
				for (int i=0;i<message.length; i++){
					String content = ""; //邮件内容
					b_content = false;
					//纯文本邮件 
					if (message[i].isMimeType("text/*")){        
						content = message[i].getContent().toString();
						b_content = true;
	    			//multipart邮件
					}else if (message[i].isMimeType("multipart/*")){ 
						Multipart mp = (Multipart)message[i].getContent();
						int bodynum = mp.getCount(); 
						for (int j = 0; j< bodynum; j++){
							if (mp.getBodyPart(j).isMimeType("text/*")){
								content = (String)mp.getBodyPart(j).getContent();
								b_content = true;
								break;
							}
						}
					}else{
						logger.error("失败：账号 "+userName+" 不支持的邮件格式：" + message[i].getContentType());
						continue;
					}
					if (b_content){
						Boolean b_AllOK = true; //同一邮件，对应多个手机号，会写入多条记录到数据库，判断是否都成功
						for (int k = 2; k < user_ary.length; k ++){
							if((insertDb(user_ary[k],content))==false){
								b_AllOK = false;
								break;
							}
						}
						if(b_AllOK){
							message[i].setFlag(Flags.Flag.DELETED, true); //写入数据库成功后，服务器端删除邮件
							numOfSuccess ++;
						}else{
							numOfFailure ++;
						}
					} else{
						numOfNoText ++;
					}
				}
				logger.info("成功：连接邮箱账号"+userName+"，写入数据库邮件数量： " +numOfSuccess);
				
				if (numOfFailure>0 || numOfNoText>0){ 
					String errMsg="失败：连接邮箱账号"+userName;
					if (numOfFailure > 0 && numOfNoText>0){
						errMsg = errMsg + "；写入数据库失败的邮件数量：" +numOfFailure + "，读取到不含文本内容的邮件数量： " +numOfNoText;
					}else if(numOfFailure > 0){
						errMsg = errMsg +"；写入数据库失败的邮件数量：" +numOfFailure;
					}else{
						errMsg = errMsg +"，读取到不含文本内容的邮件数量： " +numOfNoText;
					}
					logger.error(errMsg);
					insertErrorToDb(errMsg);
				}
				
				folder.close(true);//关闭邮箱
				store.close();//关闭该账号的连接
			}
		read.close();//关闭用户资料
	} catch(Exception e) {
		logger.error("IO异常："+e.toString());
		return;
	}
	}
//1. 获得数据库和邮件服务器的配置信息
    static Properties getConfig(){
    	Properties props = new Properties();
		PropertyConfigurator.configure("config/log4j.properties");
		try {
			FileInputStream fis = new FileInputStream("config/Config.properties");
			props.load(fis);
			logger.info("成功：读取配置文件");
			return props;
		} catch (IOException e) {
			logger.error("失败：读取配置文件，应该在目录config下有config.properties文件");
			return null;
		}
    }
  //2. 根据配置信息连接mysql数据库
    static void connectToMysql(Properties props){
    	//2. to connect to DB server
	      String driverName="com.mysql.jdbc.Driver";
	      String dbURL="jdbc:mysql://"+props.getProperty("Db.host<Mysql>");
	      String dbuserName=props.getProperty("Db.userName");    //默认用户名 
	      String dbuserPwd=props.getProperty("Db.userPwd");     //密码 
		  try {
				Class.forName(driverName);
				Connection dbConn=DriverManager.getConnection(dbURL,dbuserName,dbuserPwd); 
				preStatement = dbConn.prepareStatement("insert into t_sendtask(DestNumber,Content) values (?,?)");
			    logger.info("成功：连接数据库 ："+dbURL);
			} catch (Exception e) {
				logger.error("失败：连接数据库 ："+dbURL+"，请检查如网络能ping通、防火墙允许、数据库开启和配置文件正确。");
			} 
    }
	//3. 根据配置信息连接邮件服务器
   static void connectToMail(Properties props){
    	Properties mailProp = new Properties();
	    mailProp.put("mail.host", props.getProperty("mail.host"));
	    if (isOKPingToMail(props.getProperty("mail.host"))){
	    	logger.info("成功：连接邮件服务器：" + props.getProperty("mail.host"));
	    }else{
	    	String errMsg = "失败：无法ping通邮件服务器：" + props.getProperty("mail.host");
	    	logger.error(errMsg);
	    	insertErrorToDb(errMsg);
	    	return;
	    }
		Session session = Session.getDefaultInstance(mailProp);
		try {
			store = session.getStore("pop3");
		} catch (NoSuchProviderException e) {
			logger.error("NoSuchProvider异常：" + e.toString());
		}
    }
   //判断邮件服务器是否可以ping通
   static Boolean isOKPingToMail(String s_ip){
	   Runtime runtime = Runtime.getRuntime(); // 获取当前程序的运行进对象
	   Process process = null; // 声明处理类对象
	   String line = null; // 返回行信息
	   InputStream is = null; // 输入流
	   InputStreamReader isr = null; // 字节流
	   BufferedReader br = null;
	   boolean res = false;// 结果
	   try {
	    process = runtime.exec("ping " + s_ip); // PING
	    is = process.getInputStream(); // 实例化输入流
	    isr = new InputStreamReader(is);// 把输入流转换成字节流
	    br = new BufferedReader(isr);// 从字节中读取文本
	    while ((line = br.readLine()) != null) {
	     if (line.contains("TTL")) {
	      res = true;
	      break;
	     }
	    }
	    is.close();
	    isr.close();
	    br.close();
	   } catch (IOException e) {
	    logger.error("Ping 异常：" + e.toString());
	    runtime.exit(1);
	   }
	   return res;
   }
   
    // 读取邮箱与手机号相关信息
    static InputStreamReader readUsersInfo(){
		String filePath = "config/users.txt";
        File file=new File(filePath);
        InputStreamReader read = null;
        if(file.isFile() && file.exists()){ //判断文件是否存在
			try {
				read = new InputStreamReader(new FileInputStream(file));
			} catch (FileNotFoundException e1) {
				logger.error("File not found:"+file.toString());
			}
        }else{
        	logger.error("失败：查找文件 "+filePath);
        }
        return read;
    }
    // 判断是否能正确连接到邮箱账号
    static Boolean isOkToMailAccount(String userName, String userPwd){
    	try{
			store.connect(userName, userPwd);
			return true;
			}catch (MessagingException e){
				String errMsg = "失败：连接邮箱账号" + userName+"，请检查账号，如账号是否存在，密码是否正确。";
				insertErrorToDb(errMsg);
				logger.error(errMsg);
				return false;
			}
    }
    //将程序运行的错误信息写入数据库，加上当前系统时间
    static void insertErrorToDb(String msg){
    	Date dt = new Date();
		String s_dt = dateFormat.format(dt);
		insertDb(adminPhone,s_dt + ", " + msg);
    }
	//将邮件内容和手机号信息写入数据库待发送表(t_sendtask)
    static Boolean insertDb(String phone,String msg){
		try {
			preStatement.setString(1, phone);
			preStatement.setString(2, msg);
			preStatement.executeUpdate();
			return true;
		} catch (SQLException e1) {
			logger.error("SQL语句执行异常："+e1.toString());
			return false;
		}
    }

}
