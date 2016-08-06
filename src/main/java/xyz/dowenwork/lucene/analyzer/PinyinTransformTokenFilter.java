package xyz.dowenwork.lucene.analyzer;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.util.*;

/**
 *
 * 拼音转换分词过滤器
 *
 * @author liufl
 * @author ra
 * @since 5.5.1
 */
public class PinyinTransformTokenFilter extends TokenFilter {
    /**
     * 只输出拼音
     */
    public static final int TYPE_PINYIN = 0x01;
    /**
     * 只输出缩写（首字母）
     */
    public static final int TYPE_ABBREVIATION = 0x02;
    /**
     * 同时输出拼音和缩写
     */
    public static final int TYPE_BOTH = 0x03;
    /**
     * Default max polyphone frequency
     */
    public static final int DEFAULT_MAX_POLYPHONE_FREQ = 10;

    private boolean isOutChinese = true; // 是否输出原中文开关
    private int type = TYPE_PINYIN;
    private int _minTermLength = 2; // 中文词组长度过滤，默认超过2位长度的中文才转换拼音
    private int maxPolyphoneFreq = DEFAULT_MAX_POLYPHONE_FREQ; // 多音字出现次数(缩写不同),超过不再做组合(避免内存溢出)

    private final HanyuPinyinOutputFormat outputFormat = new HanyuPinyinOutputFormat(); // 拼音转接输出格式

    private char[] curTermBuffer; // 底层词元输入缓存
    private int curTermLength; // 底层词元输入长度

    private final CharTermAttribute termAtt = (CharTermAttribute) addAttribute(CharTermAttribute.class); // 词元记录
    private final PositionIncrementAttribute positionIncrementAttribute =
            addAttribute(PositionIncrementAttribute.class); // 位置增量属性
    private final TypeAttribute typeAtt = addAttribute(TypeAttribute.class); // 类型属性
    private boolean hasCurOut = false; // 当前输入是否已输出
    private Iterator<String> termIte = null; // 拼音结果集迭代器
    private boolean termIteRead = false; // 拼音结果集迭代器赋值后是否被读取标志
    private int inputTermPosInc = 1; // 输入Term的位置增量值


    /**
     * 构造器。默认长度超过2的中文词元进行转换，转换为全拼音且保留原中文词元
     *
     * @param input 词元输入
     */
    @SuppressWarnings("unused")
    public PinyinTransformTokenFilter(TokenStream input) {
        this(input, TYPE_PINYIN);
    }

    /**
     * 构造器。默认长度超过2的中文词元进行转换，保留原中文词元
     *
     * @param input 词元输入
     * @param type  输出拼音缩写还是完整拼音 可取值：{@link #TYPE_ABBREVIATION}、{@link #TYPE_PINYIN}、{@link #TYPE_BOTH}
     */
    public PinyinTransformTokenFilter(TokenStream input, int type) {
        this(input, type, 2);
    }

    /**
     * 构造器。默认保留原中文词元
     *
     * @param input         词元输入
     * @param type          输出拼音缩写还是完整拼音 可取值：{@link #TYPE_ABBREVIATION}、{@link #TYPE_PINYIN}、{@link #TYPE_BOTH}
     * @param minTermLength 中文词组过滤长度
     */
    public PinyinTransformTokenFilter(TokenStream input, int type,
                                      int minTermLength) {
        this(input, type, minTermLength, true);
    }

    /**
     * 构造器
     *
     * @param input         词元输入
     * @param type          输出拼音缩写还是完整拼音 可取值：{@link #TYPE_ABBREVIATION}、{@link #TYPE_PINYIN}、{@link #TYPE_BOTH}
     * @param minTermLength 中文词组过滤长度
     * @param isOutChinese  是否输入原中文词元
     */
    public PinyinTransformTokenFilter(TokenStream input, int type,
                                      int minTermLength, boolean isOutChinese) {
        this(input, type, minTermLength, DEFAULT_MAX_POLYPHONE_FREQ, isOutChinese);
    }

    /**
     * @param input            词元输入
     * @param type             输出拼音缩写还是完整拼音 可取值：{@link #TYPE_ABBREVIATION}、{@link #TYPE_PINYIN}、{@link #TYPE_BOTH}
     * @param minTermLength    中文词组过滤长度
     * @param maxPolyphoneFreq 多音字出现最大次数
     * @param isOutChinese     是否输入原中文词元
     */
    public PinyinTransformTokenFilter(TokenStream input, int type,
                                      int minTermLength, int maxPolyphoneFreq, boolean isOutChinese) {
        super(input);
        this._minTermLength = minTermLength;
        this.maxPolyphoneFreq = maxPolyphoneFreq;
        if (this._minTermLength < 1) {
            this._minTermLength = 1;
        }
        if (this.maxPolyphoneFreq < 1) {
            this.maxPolyphoneFreq = Integer.MAX_VALUE;
        }
        this.isOutChinese = isOutChinese;
        this.outputFormat.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        this.outputFormat.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        this.outputFormat.setVCharType(HanyuPinyinVCharType.WITH_V);
        this.type = type;
        addAttribute(OffsetAttribute.class); // 偏移量属性
    }

    /**
     * 判断字符串中是否含有中文
     *
     * @param s 待检测文本
     * @return 中文字符数
     */
    private static int chineseCharCount(String s) {
        int count = 0;
        if ((null == s) || ("".equals(s.trim()))) {
            return count;
        }
        for (int i = 0; i < s.length(); i++) {
            if (isChinese(s.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断字符是否是中文
     *
     * @param c 待测字符
     * @return 是 {@code true} ；否 {@code false}
     */
    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /**
     * 分词过滤。<br/>
     * 该方法在上层调用中被循环调用，直到该方法返回false
     */
    public final boolean incrementToken() throws IOException {
        while (true) {
            if (this.curTermBuffer == null) { // 开始处理或上一输入词元已被处理完成
                if (!this.input.incrementToken()) { // 获取下一词元输入
                    return false; // 没有后继词元输入，处理完成，返回false，结束上层调用
                }
                // 缓存词元输入
                this.curTermBuffer = this.termAtt.buffer().clone();
                this.curTermLength = this.termAtt.length();
                this.inputTermPosInc = this.positionIncrementAttribute.getPositionIncrement();
            }
            // 处理原输入词元
            if ((this.isOutChinese) && (!this.hasCurOut) && (this.termIte == null)) {
                // 准许输出原中文词元且当前没有输出原输入词元且还没有处理拼音结果集
                this.hasCurOut = true; // 标记以保证下次循环不会输出
                // 写入原输入词元
                this.termAtt.copyBuffer(this.curTermBuffer, 0,
                        this.curTermLength);
                this.positionIncrementAttribute.setPositionIncrement(this.inputTermPosInc);
                return true; // 继续
            }
            String chinese = this.termAtt.toString();
            // 拼音处理
            if (chineseCharCount(chinese) >= this._minTermLength) {
                //有中文且符合长度限制
                try {
                    // 输出拼音（缩写或全拼）
                    Collection<String> terms = new LinkedList<>();
                    if (TYPE_PINYIN == (this.type & TYPE_PINYIN)) {
                        terms.addAll(getPinyin(chinese));
                    }
                    if (TYPE_ABBREVIATION == (this.type & TYPE_ABBREVIATION)) {
                        terms.addAll(getPinyinAbbreviation(chinese));
                    }
                    if (!terms.isEmpty()) {
                        this.termIte = terms.iterator();
                        this.termIteRead = false;
                    }
                } catch (BadHanyuPinyinOutputFormatCombination badHanyuPinyinOutputFormatCombination) {
                    badHanyuPinyinOutputFormatCombination.printStackTrace();
                }

            }
            if (this.termIte != null) {
                if (this.termIte.hasNext()) { // 有拼音结果集且未处理完成
                    String pinyin = this.termIte.next();
                    this.termAtt.copyBuffer(pinyin.toCharArray(), 0, pinyin.length());
                    if (this.isOutChinese) {
                        this.positionIncrementAttribute.setPositionIncrement(0);
                    } else {
                        this.positionIncrementAttribute.setPositionIncrement(this.termIteRead ? 0 : this.inputTermPosInc);
                    }
                    this.typeAtt.setType("pinyin");
                    this.termIteRead = true;
                    return true;
                }
            }
            // 没有中文或转换拼音失败，不用处理，
            // 清理缓存，下次取新词元
            this.curTermBuffer = null;
            this.termIte = null;
            this.hasCurOut = false; // 下次取词元后输出原词元（如果开关也准许）
        }
    }
    /**
     * 获取拼音缩写
     *
     * @param chinese 含中文的字符串，若不含中文，原样输出
     * @return 转换后的文本
     * @throws BadHanyuPinyinOutputFormatCombination
     */
    private Set<String> getPinyinAbbreviation(String chinese)
            throws BadHanyuPinyinOutputFormatCombination {
        List<String[]> pinyinList = new LinkedList<>();
        for (int i = 0; i < chinese.length(); i++) {
            String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(
                    chinese.charAt(i), this.outputFormat);
            if (pinyinArray != null && pinyinArray.length > 0) {
                pinyinList.add(pinyinArray);
            }
        }
        Set<String> abbrSet = new HashSet<>();
        Set<String> preAbbrs = new HashSet<>();

        int polyPhoneFreq = 0;

        Iterator<String[]> it = pinyinList.iterator();

        while (it.hasNext()) {
            String[] array = getDistinctPinyinAbbr(it.next());

            if (!abbrSet.isEmpty()) {
                preAbbrs.clear();
                preAbbrs.addAll(abbrSet);
                abbrSet.clear();
                for (String preAbbr : preAbbrs) {
                    // Avoid Out of memory exception
                    if (polyPhoneFreq >= maxPolyphoneFreq) {
                        // Only match the first element
                        String charPinyin = array[0];
                        abbrSet.add(preAbbr + charPinyin.substring(0, 1));
                    } else {
                        for (String charPinyin : array) {
                            abbrSet.add(preAbbr + charPinyin.substring(0, 1));
                        }
                    }
                }

            } else {
                // Add first abbr pinyin
                for (String pinyin : array) {
                    abbrSet.add(pinyin.substring(0, 1));
                }
            }

            // Polyphone
            if (array.length > 1) {
                polyPhoneFreq++;
            }
        }
        return abbrSet;
    }


    /**
     * 获取拼音
     *
     * @param chinese 含中文的字符串，若不含中文，原样输出
     * @return 转换后的文本
     * @throws BadHanyuPinyinOutputFormatCombination
     */
    private Set<String> getPinyin(String chinese)
            throws BadHanyuPinyinOutputFormatCombination {
        List<String[]> pinyinList = new ArrayList<>();
        for (int i = 0; i < chinese.length(); i++) {
            String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(
                    chinese.charAt(i),  this.outputFormat);
            if (pinyinArray != null && pinyinArray.length > 0) {
                pinyinList.add(pinyinArray);
            }
        }

        Set<String> abbrSet = new HashSet<>();
        Set<String> preAbbrs = new HashSet<>();

        int polyPhoneFreq = 0;

        Iterator<String[]> it = pinyinList.iterator();

        while (it.hasNext()) {

            String[] array = getDistinctPinyin(it.next());
            if (!abbrSet.isEmpty()) {
                preAbbrs.clear();
                preAbbrs.addAll(abbrSet);
                abbrSet.clear();
                for (String preAbbr : preAbbrs) {
                    // Avoid Out of memory exception
                    if (polyPhoneFreq >= maxPolyphoneFreq) {
                        // Only match the first element
                        String charPinyin = array[0];
                        abbrSet.add(preAbbr + charPinyin);
                    } else {
                        for (String charPinyin : array) {
                            abbrSet.add(preAbbr + charPinyin);
                        }
                    }
                }
            } else {
                // Add first pinyin
                Collections.addAll(abbrSet, array);
            }
            // Polyphone
            if (array.length > 1) {
                polyPhoneFreq++;
            }

        }
        return abbrSet;


    }

    private String[] getDistinctPinyinAbbr(String[] array) {
        Set<String> abbrs = new HashSet<>();
        for (String pinyin : array) {
            abbrs.add(pinyin.substring(0, 1));
        }
        String[] results = new String[abbrs.size()];
        return abbrs.toArray(results);
    }

    private String[] getDistinctPinyin(String[] array) {
        Set<String> pinyins = new HashSet<>(Arrays.asList(array));
        String[] results = new String[pinyins.size()];
        return pinyins.toArray(results);
    }
    public void reset() throws IOException {
        super.reset();
    }
}
