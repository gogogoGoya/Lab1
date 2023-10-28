import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 * 简易的面向socket编程实现的HTTP代理。
 */
public class ProxyServer {


    /**
     * 禁止访问的网址。
     */
    private static final Set<String> forbidSetList = new HashSet<>();

    /**
     * 禁止访问的用户。
     */
    private static final Set<String> forbidUserList = new HashSet<>();

    /**
     * 重定向主机map。
     */
    private static final Map<String, String> redirectHostMap = new HashMap<>();

    /**
     * 重定向访问网址map。
     */
    private static final Map<String, String> redirectAddrMap = new HashMap<>();

    /**
     * 判断site是否被禁止访问.
     *
     * @param site 网址/主机/访问资源
     * @return true表示禁止访问，否则为false
     */
    private static boolean isForbidden(String site) {
        return forbidSetList.contains(site);
    }

    /**
     * 重定向主机.
     *
     * @param oriHost 原始主机
     * @return 根据redirectHostMap重定向后的主机
     */
    private static String redirectHost(String oriHost) {
        Set<String> keywordSet  = redirectHostMap.keySet();
        for (String keyword : keywordSet) {
            if (oriHost.contains(keyword)) {
                System.out.println("originHost: " + oriHost);
                String redHost = redirectHostMap.get(oriHost);
                System.out.println("redirectHost: " + redHost);
                return redHost;
            }
        }
        return oriHost;
    }

    /**
     * 重定向访问地址
     *
     * @param oriAddr 想访问的地址
     * @return 根据redirectAddrMap重定向后的访问地址
     */
    private static String redirectAddr(String oriAddr) {
        Set<String> keywordSet = redirectAddrMap.keySet();
        for (String keyword : keywordSet) {
            if (oriAddr != null && oriAddr.contains(keyword)) {
                System.out.println("originAddr: " + oriAddr);
                String redAddr = redirectAddrMap.get(oriAddr);
                System.out.println("redirectAddr: " + redAddr);
                return redAddr;
            }
        }
        return oriAddr;
    }
    static {
        //屏蔽指定网址
//        forbidSetList.add("http://jwts.hit.edu.cn/");
//        forbidSetList.add("jwts.hit.edu.cn");
        //屏蔽指定用户
//        forbidUserList.add("127.0.0.1");
        //设置用于钓鱼的源地址和目的地址
//        redirectAddrMap.put("http://jwes.hit.edu.cn/", "http://www.example.com/");
//        redirectHostMap.put("jwes.hit.edu.cn", "www.example.com");
    }
    /**
     * 通过header解析各个参数.
     *
     * @param header HTTP报文头部
     * @return map，包含HTTP版本，method，访问内容，主机，端口
     */
    private static Map<String, String> parseTelegram(String header) {
        if (header.length() == 0) {
            return new HashMap<>();
        }
        String[] lines = header.split("\\n");
        String method = null;
        String visitAddr = null;
        String httpVersion = null;
        String hostName = null;
        String portString = null;
        for (String line : lines) {
            if ((line.contains("GET") || line.contains("POST") || line.contains("CONNECT")) && method == null) {
                // 这一行包括get xxx httpVersion
                String[] temp = line.split("\\s");  // 按空格分割
                method = temp[0];
                visitAddr = temp[1];
                httpVersion = temp[2];
                // 先判断是否包含http://关键字
                String[] temp1 = visitAddr.split(":");
                if (visitAddr.contains("http://") || visitAddr.contains("https://")) {
                    if (temp1.length >= 3) {    // 因为有http://带来的冒号，所以如果长度>=3则有端口号
                        portString = temp1[2];   // 且temp[1]是host,temp[2]是port
                    }
                } else {
                    if (temp1.length >= 2) { // 不包含http,长度>=2则有端口号
                        portString = temp1[1];
                    }
                }
            } else if (line.contains("Host: ") && hostName == null) {  //解析HTTP请求报文中的 "Host" 头部信息，以提取主机名
                hostName = line.split("\\s")[1];
                int machoIndex = hostName.indexOf(':');
                if (machoIndex != -1) {
                    hostName = hostName.substring(0, machoIndex);
                }
            }
        }

        Map<String, String> map = new HashMap<>();
        // 构造参数map
        map.put("method", method);
        map.put("visitAddr", visitAddr);
        map.put("httpVersion", httpVersion);
        map.put("host", hostName);
        map.put("port", Objects.requireNonNullElse(portString, "80"));
        return map;
    }

    /**
     * 重新编写发送给远程服务器的报文
     * @param host 主机号
     * @param method 请求方法
     * @param visitAddr 访问地址
     * @param lastModified 最近修改时间
     * @return 重构后的报文
     */
    private static StringBuilder SetRemoteTemplate(String host, String method, String visitAddr, String lastModified){
        StringBuilder requestBuffer = new StringBuilder();
        requestBuffer.append(method).append(" ").append(visitAddr)
                .append(" HTTP/1.1").append("\r\n")
                .append("HOST: ").append(host).append("\n")
                .append("Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8\n")
                .append("Accept-Encoding:gzip, deflate, sdch\n")
                .append("Accept-Language:zh-CN,zh;q=0.8\n")
                .append("If-Modified-Since: ").append(lastModified).append("\n")     //加入if modified since
                .append("User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.2 Safari/605.1.15\n")
                .append("Encoding:UTF-8\n")
                .append("Connection:keep-alive" + "\n")
                .append("\n");

        return requestBuffer;
    }

    /**
     * /**
     * 实现缓存文件的管理
     * @param ServerSocket 连接远程服务器的socket
     * @param header 报文首部
     * @param host 主机号
     * @param visitAddr 目标地址
     * @param method 报文方法
     * @param ClientSocketOut 面向客户端的输出流
     * @param ServerSocketOut 面向远程服务器的输出流
     * @param ServerSocketIn 面向远程服务器的输入流
     * @throws IOException
     */
    private static void CacheManage(Socket ServerSocket, StringBuilder header,
                                    String host, String visitAddr, String method,
                                    OutputStream ClientSocketOut, OutputStream ServerSocketOut, InputStream ServerSocketIn) throws IOException {
        boolean useCache = false;  //用于判断是否使用缓存
        System.out.println(host);

        //设置缓存文件,以文件形式存储缓存内容
        String dir = "cache" + File.separator + host + ".txt";
        File file = new File(dir);
        File folder = new File("cache");
        for(File file0: Objects.requireNonNull(folder.listFiles()))
        {   //查找当前访问网页是否存在对应缓存文件
            BufferedReader fileReader=new BufferedReader(new InputStreamReader(new FileInputStream(file0)));
            if(fileReader.readLine().equals(visitAddr))//缓存命中
            {
                System.out.println(visitAddr+"-->-->缓存命中!");
                useCache = true;
                file = file0;
                break;
            }
        }

        if(useCache)
        {
            Scanner sc =new Scanner (new FileReader(file));
            String line,lastModifiedDate=null;
            while(sc.hasNextLine())
            {
                //扫描缓存文件并查找其中的存储的“最近更新时间”
                line = sc.nextLine();
                if(line.contains("Date")){
                    lastModifiedDate = line.substring(6);
                }
            }
            //重新编写发送给远程服务器的报文
            String remoteRequest = String.valueOf(SetRemoteTemplate(host,method,visitAddr,lastModifiedDate));
            ServerSocketOut.write(remoteRequest.getBytes());  //将报文通过socket写入远程服务器
            ServerSocketOut.flush();
            byte[] tempBytes = new byte[30];
            int len = ServerSocketIn.read(tempBytes);
            String res = new String(tempBytes, 0, len);  //获取远程信息反馈
            if(res.contains("304")) //状态码304 意味着最新修改时间一致，缓存不需要更新，可直接使用
            {
                System.out.println("缓存内容未更新，直接使用");
                //读取缓存文件
                BufferedInputStream inputStream=new BufferedInputStream(new FileInputStream(file));
                String address=visitAddr+"\r\n";
                inputStream.read(address.getBytes());  //在缓存文件的开头记录访问的地址
                int length;
                byte[] bytes=new byte[1024]; //用于临时存储从缓存文件中读取的数据
                ServerSocket.shutdownOutput();  //关闭代理服务器与远程服务器之间的输出流
                while((length=inputStream.read(bytes))!=-1){
                    ClientSocketOut.write(bytes,0,length);  //将从缓存文件中读取的数据写入到代理服务器与客户端之间的输出流
                }
                ClientSocketOut.flush();  //刷新输出流
            }
            else
            {   //此时状态为200，意味着目标地址内容已被修改过，需要更新缓存
                System.out.println("缓存内容更新，重新缓存并使用");
                //删除并重建缓存文件
                file.delete();
                file.createNewFile();
                FileOutputStream outputStream=new FileOutputStream(file);
                String address=visitAddr+"\r\n";
                outputStream.write(address.getBytes(StandardCharsets.UTF_8));
                outputStream.write(tempBytes,0,len);   //将之前从远程服务器读取的响应数据写入到缓存文件中
                ClientSocketOut.write(tempBytes,0,len);   //同时写入代理服务器与客户端之间的输出流，加快响应速度
                ServerSocket.shutdownOutput();  //传输完后关闭输出流
                BufferedInputStream inputStream=new BufferedInputStream(ServerSocketIn);  //从远程服务器读取数据
                byte[] buf = new byte[1024];
                int size;
                while (( size = inputStream.read(buf)) != -1) {
                    ClientSocketOut.write(buf,0,size);
                    outputStream.write(buf,0,size);
                }
                ClientSocketOut.flush();
                outputStream.flush();
            }
        }
        else
        {
            ServerSocketOut.write(header.toString().getBytes(StandardCharsets.UTF_8));
            ServerSocketOut.flush();
            ServerSocket.shutdownOutput();
            //如果缓存不存在，则将服务器传来的数据传给客户端并且缓存
            System.out.println(visitAddr+"	miss!缓存未命中");
            //缓存未命中的处理与缓存命中但需要更新的处理是很相似的
            file.createNewFile();
            FileOutputStream outputStream=new FileOutputStream(file);
            String pad=visitAddr+"\r\n";
            outputStream.write(pad.getBytes(StandardCharsets.UTF_8));
            BufferedInputStream inputStream=new BufferedInputStream(ServerSocketIn);
            byte[] buf = new byte[1];
            int size;
            while (( size = inputStream.read(buf)) != -1) {
                ClientSocketOut.write(buf,0,size);
                outputStream.write(buf,0,size);
            }
            ClientSocketOut.flush();
            outputStream.flush();
        }
    }

    /**
     * 执行代理相关功能
     */
    static class ProxyHandler implements Runnable {
        private final Socket ClientSocket;
        public ProxyHandler(Socket ClientSocket) {
            this.ClientSocket = ClientSocket;
        }
        // 创建缓存
        @Override
        public void run() {
            try {
                // 解析header
                InputStreamReader r = new InputStreamReader(ClientSocket.getInputStream());  //读取客户端传输的信息
                BufferedReader br = new BufferedReader(r);
                String readLine = br.readLine();
                StringBuilder header = new StringBuilder();

                OutputStream ClientSocketOut = ClientSocket.getOutputStream(); //用于从代理服务器向客户端传递信息
                while (readLine != null && !readLine.equals("")) {
                    header.append(readLine).append("\n");
                    readLine = br.readLine();
                }

                // 在输入流结束之后判断
                // 判断用户是否被屏蔽
                if (forbidUserList.contains(ClientSocket.getInetAddress().getHostAddress())) {
                    System.out.println("当前用户已被禁止访问");
                    PrintWriter pw = new PrintWriter(ClientSocket.getOutputStream());
                    pw.println("The current user has been banned");
                    pw.close();
                    ClientSocket.close();
                    return;
                }

                Map<String, String> map = parseTelegram(header.toString());   // 获取参数表
                //获取各参数
                String host = map.get("host"); // 主机号
                int visitPort = Integer.parseInt(map.getOrDefault("port", "80")); // 端口号，默认80访问http
                String visitAddr = map.get("visitAddr");   // 目标网站地址
                String method = map.getOrDefault("method", "GET");   // 访问方式
                //网络运行中会收到很多面向https的请求，本代理只能处理http，所以将端口号为443的https请求屏蔽，只接受能处理的信息
                if(visitPort==443) return;
                //打印信息表
                System.out.println("获取到一个来自 " + ClientSocket.getInetAddress().getHostAddress() + "的连接");
                System.out.println("-------------------");
                System.out.println(map);
                if (visitAddr != null && isForbidden(visitAddr)) {
                    // 被屏蔽，不允许访问
                    System.out.println("该站点已被禁止访问");
                    PrintWriter pw = new PrintWriter(ClientSocket.getOutputStream());
                    pw.println("This site is forbidden to be visited");
                    pw.close();
                }else if(visitAddr != null && !visitAddr.equals("")){
                    String tempRedAddr = redirectAddr(visitAddr);  //判断当前访问地址是否需要被重定向
                    if (!tempRedAddr.equals(visitAddr)) {
                        visitAddr = tempRedAddr;
                        host = redirectHost(host);
                    }
                    Socket ServerSocket = new Socket(host,visitPort);   //设置连接远程服务器的socket
                    OutputStream ServerSocketOut = ServerSocket.getOutputStream();
                    InputStream ServerSocketIn = ServerSocket.getInputStream();
                    if (method.equals("GET")) {    //只有method为GET时才能使用缓存技术
                        CacheManage(ServerSocket,header,host,visitAddr,method,ClientSocketOut,ServerSocketOut,ServerSocketIn);
                    }
                }
                else
                {   //无法使用缓存时,由socket架构客户端和远程服务器的数据传输
                    Socket ServerSocket = new Socket(host,visitPort);
                    OutputStream ServerSocketOut = ServerSocket.getOutputStream(); //用于将数据写入远程服务器
                    InputStream ServerSocketIn = ServerSocket.getInputStream(); //用于从远程服务器接收数据
                    ServerSocketOut.write(header.toString().getBytes());
                    ServerSocketOut.flush();
                    ServerSocket.shutdownOutput();
                    BufferedInputStream inputStream=new BufferedInputStream(ServerSocketIn); //创建一个带缓冲的数据流以提供读取效率
                    byte[] buf = new byte[1];
                    int size;
                    while (( size = inputStream.read(buf)) != -1) {
                        ClientSocketOut.write(buf,0,size);      //将远程服务器的数据传给客户端
                    }
                    ClientSocketOut.flush();
                }
                ClientSocket.close();// 关闭浏览器与程序的socket
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        // 监听指定的端口
        int port = 8080;
        ServerSocket server = new ServerSocket(port);
        // server将一直等待连接的到来
        System.out.println("server将一直等待连接的到来");

        //创建一个线程池
        ExecutorService threadPool = Executors.newFixedThreadPool(100);
        while (true) {
            try{
                Socket socket = server.accept();
                // 使用线程池提交任务
                threadPool.submit(new ProxyHandler(socket));
            }catch (IOException e){
                e.printStackTrace();
                break;
            }
        }
    }
}