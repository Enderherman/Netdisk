package top.enderherman.netdisk.common.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Random;

public class ImageGenerator {
    // 图片的宽度
    private int width = 160;
    // 图片的高度
    private int height = 40;
    // 验证码字符个数
    private int codeCount = 4;
    // 验证码干扰线数
    private int lineCount = 20;
    // 验证码
    private String code = null;
    // 验证码图片Buffer
    private BufferedImage buffImg = null;
    Random random = new Random();

    public ImageGenerator() {
        creatImage();
    }

    public ImageGenerator(int width, int height) {
        this.width = width;
        this.height = height;
        creatImage();
    }

    public ImageGenerator(int width, int height, int codeCount) {
        this.width = width;
        this.height = height;
        this.codeCount = codeCount;
        creatImage();
    }

    public ImageGenerator(int width, int height, int codeCount, int lineCount) {
        this.width = width;
        this.height = height;
        this.codeCount = codeCount;
        this.lineCount = lineCount;
        creatImage();
    }

    // 生成图片
    private void creatImage() {

        int fontWidth = width / codeCount;// 字体的宽度
        int fontHeight = height - 5;// 字体的高度
        int codeY = height - 8;

        //1.图像buffer
        buffImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = buffImg.getGraphics();
        //Graphics2D g = buffImg.createGraphics();

        //2.设置背景色
        g.setColor(getRandomColor(200, 250));
        //铺满整个背景
        g.fillRect(0, 0, width, height);

        //3.设置字体
        //Font font1 = getFont(fontHeight);
        Font font = new Font("Fixedsys", Font.BOLD, fontHeight);
        g.setFont(font);

        //4.设置干扰线
        for (int i = 0; i < lineCount; i++) {
            int xs = random.nextInt(width);
            int ys = random.nextInt(height);
            int xe = xs + random.nextInt(width);
            int ye = ys + random.nextInt(height);
            g.setColor(getRandomColor(1, 255));
            g.drawLine(xs, ys, xe, ye);
        }

        //5.添加噪点
        float yawpRate = 0.01f;// 噪声率
        int area = (int) (yawpRate * width * height);
        for (int i = 0; i < area; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            buffImg.setRGB(x, y, random.nextInt(255));
        }

        //6.放入字符
        String code = getRandomStr(codeCount);// 得到随机字符
        this.code = code;
        for (int i = 0; i < codeCount; i++) {
            String strRand = code.substring(i, i + 1);
            g.setColor(getRandomColor(1, 255));
            // g.drawString(a,x,y);
            // a为要画出来的东西，x和y表示要画的东西最左侧字符的基线位于此图形上下文坐标系的 (x, y) 位置处

            g.drawString(strRand, i * fontWidth + 3, codeY);
        }
    }

    /**
     * 获取随机颜色
     *
     * @param lower 颜色范围下界
     * @param upper 颜色范围上界
     * @return 随机颜色
     */
    private Color getRandomColor(int lower, int upper) {// 给定范围获得随机颜色
        if (lower > 255) lower = 255;
        if (upper > 255) upper = 255;
        int red = lower + random.nextInt(upper - lower);
        int green = lower + random.nextInt(upper - lower);
        int blue = lower + random.nextInt(upper - lower);
        return new Color(red, green, blue);
    }

    /**
     * 得到指定长度的验证码字符
     *
     * @param n 验证码长度
     * @return 验证码
     */
    private String getRandomStr(int n) {
        String originStr = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz234567890";
        StringBuilder result = new StringBuilder();
        int len = originStr.length() - 1;
        double randomIndex;
        for (int i = 0; i < n; i++) {
            randomIndex = (Math.random()) * len;
            result.append(originStr.charAt((int) randomIndex));
        }
        return result.toString();
    }

    /**
     * 将图片写入到输出流中
     * @param sos 目标输出流
     * @throws IOException io异常
     */
    public void write(OutputStream sos) throws IOException {
        ImageIO.write(buffImg, "png", sos);
        sos.close();
    }

    /**
     * 返回小写的验证码
     *
     * @return 验证码
     */
    public String getCode() {
        return this.code.toLowerCase(Locale.ROOT);
    }
}
