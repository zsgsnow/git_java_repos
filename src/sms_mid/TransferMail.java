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
import java.util.regex.Pattern;

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
	 * ���ܣ���ȡ�ʼ�ת���������м��mysql���ݿ�
	 * ���裺
	 * 1���Ȼ�����ݿ���ʼ���������������Ϣ
	 * 2������������Ϣ����mysql���ݿ�
	 * 3������������Ϣ�����ʼ�������
	 * 4����ȡ�ʼ�ת�������ݿ�
	 * @author Zsg
	 */
 static Logger logger = Logger.getLogger(TransferMail.class);
 static String adminPhone = null;//����Ա�ֻ��ţ����д�����Ϣ�����������ֻ�
 static PreparedStatement  preStatement = null;
 static Store store = null;
 static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm");//���������ʱ���ʽ
 static Pattern p_mobile = Pattern.compile("^((13[0-9])|(15[^4,\\D])|(18[0,5-9]))\\d{8}$");  //�ж������ļ�config/users.txt���ֻ����Ƿ���ȷ
 
 public static void main(String[] args) {
		//1. ������ݿ���ʼ���������������Ϣ
		Properties props = getConfig();
		if (props == null) return; 
		adminPhone=props.getProperty("Admin.phone");   //����ƽ̨����Ա�ֻ���
		//2. ����������Ϣ����mysql���ݿ�
	     connectToMysql(props);
	     if (preStatement == null) return;
		  
		//3. ����������Ϣ�����ʼ�������
	     connectToMail(props);
	     if (store == null) return;
		
		//4. ��ȡ�ʼ�ת�������ݿ�
	    InputStreamReader read = readUsersInfo();
		if (read == null) return;
        BufferedReader bufferedReader = new BufferedReader(read); 
        String lineTxt =null;
		String userName =null;
		String userPwd =null;
		Boolean b_content = false; //�ж��ʼ��ǰ����ı���Ϣ 
		try {
			while((lineTxt = bufferedReader.readLine()) != null){
				if(lineTxt.trim().startsWith("#")) continue;
				String[] userInfo = lineTxt.trim().split("\\s+");//�ո�ָ�һ���û���Ϣ
				
				if(isOKUserInfo(userInfo)==false) continue; //������һ���������ֻ���Ӧ����
				
				userName = userInfo[0];
				userPwd =  userInfo[1];
				if (isOKToMailAccount(userName,userPwd) == false) continue; //���˺Ų������ӣ�������һ���˺�
				Folder folder = store.getFolder("INBOX");
				folder.open(Folder.READ_WRITE);
				Message message[] = folder.getMessages();
				logger.info("�ɹ������������˺�" + userName + "����ȡ�ʼ�������" + message.length);
				int numOfSuccess =0; //��¼ÿ���˺�д�����ݿ���ʼ����� 
				int numOfNoText =0;  //��¼ÿ���˺Ų����ı���Ϣ���ʼ�����
				int numOfFailure =0; //��¼ÿ���˺�д�����ݿ�ʧ�ܵ��ʼ�����
				for (int i=0;i<message.length; i++){
					String content = ""; //�ʼ�����
					b_content = false;
					//���ı��ʼ� 
					if (message[i].isMimeType("text/*")){        
						content = message[i].getContent().toString();
						b_content = true;
	    			//multipart�ʼ�
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
						logger.error("ʧ�ܣ��˺� "+userName+" ��֧�ֵ��ʼ���ʽ��" + message[i].getContentType());
						continue;
					}
					if (b_content){
						Boolean b_AllOK = true; //ͬһ�ʼ�����Ӧ����ֻ��ţ���д�������¼�����ݿ⣬�ж��Ƿ񶼳ɹ�
						for (int k = 2; k < userInfo.length; k ++){
							if((insertDb(userInfo[k],content))==false){
								b_AllOK = false;
								break;
							}
						}
						if(b_AllOK){
							message[i].setFlag(Flags.Flag.DELETED, true); //д�����ݿ�ɹ��󣬷�������ɾ���ʼ�
							numOfSuccess ++;
						}else{
							numOfFailure ++;
						}
					} else{
						numOfNoText ++;
					}
				}
				logger.info("�ɹ������������˺�"+userName+"��д�����ݿ��ʼ������� " +numOfSuccess);
				
				if (numOfFailure>0 || numOfNoText>0){ 
					String errMsg="ʧ�ܣ����������˺�"+userName;
					if (numOfFailure > 0 && numOfNoText>0){
						errMsg = errMsg + "��д�����ݿ�ʧ�ܵ��ʼ�������" +numOfFailure + "����ȡ�������ı����ݵ��ʼ������� " +numOfNoText;
					}else if(numOfFailure > 0){
						errMsg = errMsg +"��д�����ݿ�ʧ�ܵ��ʼ�������" +numOfFailure;
					}else{
						errMsg = errMsg +"����ȡ�������ı����ݵ��ʼ������� " +numOfNoText;
					}
					logger.error(errMsg);
					insertErrorToDb(errMsg);
				}
				
				folder.close(true);//�ر�����
				store.close();//�رո��˺ŵ�����
			}
		read.close();//�ر��û�����
	} catch(Exception e) {
		logger.error("IO�쳣��"+e.toString());
		return;
	}
	} 
   /**
    * ������ݿ���ʼ���������������Ϣ 
    * @return Properties
    * ������Ϣ����
    */
    static Properties getConfig(){
    	Properties props = new Properties();
		PropertyConfigurator.configure("config/log4j.properties");
		try {
			FileInputStream fis = new FileInputStream("config/Config.properties");
			props.load(fis);
			logger.info("�ɹ�����ȡ�����ļ�");
			return props;
		} catch (IOException e) {
			logger.error("ʧ�ܣ���ȡ�����ļ���Ӧ����Ŀ¼config����config.properties�ļ�");
			return null;
		}
    }

    /**
     * ����������Ϣ����mysql���ݿ�
     * @param props
     * ������Ϣconfig/Config.properties�ļ�����
     * @return void
     */
    static void connectToMysql(Properties props){
    	//2. to connect to DB server
	      String driverName="com.mysql.jdbc.Driver";
	      String dbURL="jdbc:mysql://"+props.getProperty("Db.host<Mysql>");
	      String dbuserName=props.getProperty("Db.userName");    //Ĭ���û��� 
	      String dbuserPwd=props.getProperty("Db.userPwd");     //���� 
		  try {
				Class.forName(driverName);
				Connection dbConn=DriverManager.getConnection(dbURL,dbuserName,dbuserPwd); 
				preStatement = dbConn.prepareStatement("insert into t_sendtask(DestNumber,Content) values (?,?)");
			    logger.info("�ɹ����������ݿ� ��"+dbURL);
			} catch (Exception e) {
				logger.error("ʧ�ܣ��������ݿ� ��"+dbURL+"��������������pingͨ������ǽ�������ݿ⿪���������ļ���ȷ��");
			} 
    }
    
    /**
     * ����������Ϣ�����ʼ�������
     * @param props 
     * ������Ϣ
     * @return void
     */
   static void connectToMail(Properties props){
    	Properties mailProp = new Properties();
	    mailProp.put("mail.host", props.getProperty("mail.host"));
	    if (isOKToMailServer(props.getProperty("mail.host"))){
	    	logger.info("�ɹ��������ʼ���������" + props.getProperty("mail.host"));
	    }else{
	    	String errMsg = "ʧ�ܣ��޷�pingͨ�ʼ���������" + props.getProperty("mail.host");
	    	logger.error(errMsg);
	    	insertErrorToDb(errMsg);
	    	return;
	    }
		Session session = Session.getDefaultInstance(mailProp);
		try {
			store = session.getStore("pop3");
		} catch (NoSuchProviderException e) {
			logger.error("NoSuchProvider�쳣��" + e.toString());
		}
    }

   /**
    * �ж��ʼ��������Ƿ��������
    * @param s_ip
    * �ʼ���������IP��ַ
    * @return boolean
    * true ��ʾ������
    * false ��ʾ��������
    */
   static boolean isOKToMailServer(String s_ip){
	   Runtime runtime = Runtime.getRuntime(); // ��ȡ��ǰ��������н�����
	   Process process = null; // �������������
	   String line = null; // ��������Ϣ
	   InputStream is = null; // ������
	   InputStreamReader isr = null; // �ֽ���
	   BufferedReader br = null;
	   boolean res = false;// ���
	   try {
	    process = runtime.exec("ping " + s_ip); // PING
	    is = process.getInputStream(); // ʵ����������
	    isr = new InputStreamReader(is);// ��������ת�����ֽ���
	    br = new BufferedReader(isr);// ���ֽ��ж�ȡ�ı�
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
	    logger.error("Ping �쳣��" + e.toString());
	    runtime.exit(1);
	   }
	   return res;
   }
   
   /**
    * ��ȡ�������ֻ��������Ϣ config/users.txt
    * @return InputStreamReader
    */
    static InputStreamReader readUsersInfo(){
		String filePath = "config/users.txt";
        File file=new File(filePath);
        InputStreamReader read = null;
        if(file.isFile() && file.exists()){ //�ж��ļ��Ƿ����
			try {
				read = new InputStreamReader(new FileInputStream(file));
			} catch (FileNotFoundException e1) {
				logger.error("File not found:"+file.toString());
			}
        }else{
        	logger.error("ʧ�ܣ������ļ� "+filePath);
        }
        return read;
    }
    //
    /**
     * �ж��Ƿ������ӵ������˺�
     * @param userName
     * �����˺�
     * @param userPwd
     * �˺�����
     * @return boolean
     * true ��ʾ������
     * false ��ʾ��������
     */
    static boolean isOKToMailAccount(String userName, String userPwd){
    	try{
			store.connect(userName, userPwd);
			return true;
			}catch (MessagingException e){
				String errMsg = "ʧ�ܣ����������˺�" + userName+"�������˺ţ����˺��Ƿ���ڣ������Ƿ���ȷ��";
				insertErrorToDb(errMsg);
				logger.error(errMsg);
				return false;
			}
    }
    //
    /**
     * ���������еĴ�����Ϣ���ϵ�ǰϵͳʱ��д�����ݿ⣬
     * @param msg
     * ���еĴ�����Ϣ
     * @return void
     */
    static void insertErrorToDb(String msg){
    	Date dt = new Date();
		String s_dt = dateFormat.format(dt);
		insertDb(adminPhone,s_dt + ", " + msg);
    }
	
    /**
     * ���ʼ����ݺ��ֻ�����Ϣд�����ݿ�����ͱ�(t_sendtask)
     * @param phone
     * �ֻ�����
     * @param msg
     * �����͵�����
     * @return boolean
     * true ��ʾд��ɹ�
     * false ��ʾд��ʧ��
     */
    static boolean insertDb(String phone,String msg){
		try {
			preStatement.setString(1, phone);
			preStatement.setString(2, msg);
			preStatement.executeUpdate();
			return true;
		} catch (SQLException e1) {
			logger.error("SQL���ִ���쳣��"+e1.toString());
			return false;
		}
    }
    //
    /**
     * �ж��û�������Ϣconfig/users.txt�����Ƿ���ȷ
     * @param userInfo
     * config/users.txt����
     * @return boolean
     * true ��ʾ������Ϣ��ȷ
     * false ��ʾ������Ϣ����
     */
    static boolean isOKUserInfo(String[] userInfo){
    	if (userInfo.length<3) {
    		logger.error("ʧ�ܣ������û����� , Ӧ������: �û���	����	�ֻ���      ������,���Զ���ֻ���");
    		return false;
    	}
    	for (int i = 2;i<userInfo.length;i++){//�ж�������ֻ��ţ��Ƿ���ȷ
    		if (p_mobile.matcher(userInfo[i]).matches()==false) {
    			logger.error("�����ֻ���" + userInfo[i]+"�����������ļ�config/users.txt�У��˺�" +userInfo[0] + "���ʼ�δת����");
    			return false;
    		}
    	}
    	return true;
    }

}
