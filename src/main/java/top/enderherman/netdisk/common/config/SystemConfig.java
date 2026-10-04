package top.enderherman.netdisk.common.config;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.io.Serializable;


@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemConfig implements Serializable {
    private String registerEmailTitle = "欢迎使用原梦云";
    private String registerEmailContent = "您好, 您的验证码为: %s, 15分钟有效";
    /**
     * 初始空间容量
     */
    private Integer userInitUseSpace = 50;


    public void setRegisterEMailTitle(String registerMailTitle) {
        this.registerEmailTitle = registerMailTitle;
    }

    public String getRegisterEmailContent() {
        return registerEmailContent;
    }

    public void setRegisterEmailContent(String registerEmailContent) {
        this.registerEmailContent = registerEmailContent;
    }

    public Integer getUserInitUseSpace() {
        return userInitUseSpace;
    }

    public void setUserInitUseSpace(Integer userInitUseSpace) {
        this.userInitUseSpace = userInitUseSpace;
    }

    public String getRegisterEmailTitle() {
        return registerEmailTitle;

    }

}
