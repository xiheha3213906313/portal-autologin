package local.portal.autologin;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;

public class ReleaseNotesTextTest {
    @Test public void headingAndEmphasisMarkersAreHiddenWithoutLosingTheirWords() {
        assertEquals("v1.4.1\n\nLSPosed 迁移\n- 改用 Modern API，保留配置。",
            ReleaseNotesText.display("v1.4.1\n\n### LSPosed 迁移 ###\n- 改用 **Modern API**，保留配置。"));
    }

    @Test public void allListStylesUseHyphensIncludingNumberedAndTaskItems() {
        assertEquals("- 第一条\n- 第二条\n- 第三条\n- 完成\n- 待办\n- 最后一条",
            ReleaseNotesText.display("* 第一条\n+ 第二条\n1. 第三条\n- [x] 完成\n- [ ] 待办\n2) 最后一条"));
    }

    @Test public void inlineFormattingDoesNotDamageVersionsIdentifiersOrOrdinaryPunctuation() {
        assertEquals("- 升级到 1.4.1，支持 C#、C++ 和 private_account_presets.json\n强调、斜体、删除、加粗",
            ReleaseNotesText.display("- 升级到 `1.4.1`，支持 C#、C++ 和 `private_account_presets.json`\n***强调***、*斜体*、~~删除~~、__加粗__"));
    }

    @Test public void linksRetainTheirLabelAndDestinationWithoutMarkdownDelimiters() {
        assertEquals("- 项目（https://example.com/a_b）\n图标",
            ReleaseNotesText.display("- [项目](https://example.com/a_b)\n![图标](https://example.com/icon.png)"));
    }

    @Test public void quotesSeparatorsAndCodeFencesDoNotShowFormattingSymbols() {
        assertEquals("说明\n\n重新打开软件\n内容",
            ReleaseNotesText.display("> 说明\r\n\r\n\r\n---\r\n```text\r\n重新打开软件\r\n```\r\n内容\r\n===\r\n"));
    }

    @Test public void cumulativeVersionsAndEveryReleaseDetailRemainVisible() {
        ReleaseHistory.Entry latest=new ReleaseHistory.Entry("1.4.1",
            "## 更新内容\n### 接口\n- 保留 **全部** 内容\n## 安装\n隐藏安装段",false,false);
        ReleaseHistory.Entry earlier=new ReleaseHistory.Entry("1.4.0",
            "## 更新内容\n### 后台\n- 增加 `后台自动登录`",false,false);
        ReleaseHistory.Result history=ReleaseHistory.collect("1.3.5",latest,
            number->new ReleaseHistory.Page(Collections.singletonList(earlier),false));
        assertEquals("v1.4.1\n接口\n- 保留 全部 内容\n\nv1.4.0\n后台\n- 增加 后台自动登录",
            ReleaseNotesText.display(history.notes));
        assertTrue(latest.body.contains("###"));
    }

    @Test public void emptyInputIsSafeAndLongPlainTextIsNotTruncated() {
        assertEquals("",ReleaseNotesText.display(null));
        assertEquals("",ReleaseNotesText.display("\n  \n"));
        String content=String.join("",Collections.nCopies(4000,"内容"))+"END";
        assertEquals(content,ReleaseNotesText.display(content));
    }
}
