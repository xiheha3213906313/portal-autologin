package local.portal.autologin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ProjectLinks {
    static final String REPOSITORY="https://github.com/xiheha3213906313/portal-autologin";
    static final String LATEST_API="https://api.github.com/repos/xiheha3213906313/portal-autologin/releases/latest";
    static String issueDraft(String project,String username,String password) {
        project=project==null?"":project.trim();
        if(project.isEmpty() || project.length()>80 || project.contains("\n") || project.contains("\r"))
            throw new IllegalArgumentException("invalid_project_name");
        if(username==null || password==null || username.length()>4096 || password.length()>4096)
            throw new IllegalArgumentException("invalid_account");
        String body="### 项目名称\n"+fenced(project)+"\n### 账号\n"+fenced(username)+"\n### 密码\n"+fenced(password);
        return REPOSITORY+"/issues/new?title="+encode("[账号分享] "+project)+"&body="+encode(body);
    }
    private static String encode(String s) {
        try{return URLEncoder.encode(s,StandardCharsets.UTF_8.name());}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError();}
    }
    private static String fenced(String s) {
        int longest=0,run=0;
        for(char c:s.toCharArray()){run=c=='`'?run+1:0;longest=Math.max(longest,run);}
        char[] chars=new char[Math.max(3,longest+1)];Arrays.fill(chars,'`');String fence=new String(chars);
        return fence+"\n"+s+"\n"+fence+"\n";
    }
}
