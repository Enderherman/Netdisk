package top.enderherman.netdisk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class NetdiskApplication {

    public static void main(String[] args) {
        System.out.println("""
                ////////////////////////////////////////////////////////////////////
                //                          _ooOoo_                               //
                //                         o8888888o                              //
                //                         88" . "88                              //
                //                         (| ^_^ |)                              //
                //                         O\\  =  /O                              //
                //                      ____/`---'\\____                           //
                //                    .'  \\\\|     |//  `.                         //
                //                   /  \\\\|||  :  |||//  \\                        //
                //                  /  _||||| -:- |||||-  \\                       //
                //                  |   | \\\\\\  -  /// |   |                       //
                //                  | \\_|  ''\\---/''  |   |                       //
                //                  \\  .-\\__  `-`  ___/-. /                       //
                //                ___`. .'  /--.--\\  `. . ___                     //
                //              ."" '<  `.___\\_<|>_/___.'  >'"".                  //
                //            | | :  `- \\`.;`\\ _ /`;.`/ - ` : | |                 //
                //            \\  \\ `-.   \\_ __\\ /__ _/   .-` /  /                 //
                //      ========`-.____`-.___\\_____/___.-`____.-'========         //
                //                           `=---='                              //
                //      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^        //
                //            佛祖保佑       永不宕机     永无BUG                   //""");
        SpringApplication.run(NetdiskApplication.class, args);
    }
}
