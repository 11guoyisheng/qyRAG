#!/usr/bin/env python3
"""
RAG evaluation script for the local Spring AI + Milvus demo.

Usage examples:
  python eval/rag_eval.py --upload
  python eval/rag_eval.py --run-search --run-chat
  python eval/rag_eval.py --upload --run-search --run-chat --base-url http://localhost:8080

The script uses only Python standard library modules.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_BASE_URL = "http://localhost:8080"
DEFAULT_DEPARTMENT_ID = "eval"
DEFAULT_MODULE_CODE = "policy"
DEFAULT_TOP_K = 5
DEFAULT_REPORT_PATH = Path("eval/rag_eval_report.json")


EVAL_DOCUMENTS = [
    {
        "fileName": "员工考勤与请假制度",
        "chunks": [
            "员工申请年假需要至少提前三个工作日提交申请，并经过直属主管审批。连续年假超过五个工作日的，还需要部门负责人复核。",
            "员工申请病假需要在返岗后三个工作日内提交医院诊断证明或病历材料。无法提供有效证明的，病假可按事假处理。",
            "迟到超过三十分钟且未提前报备的，按半天事假处理。因公共交通大面积延误导致迟到的，可提交官方延误证明申请豁免。",
        ],
    },
    {
        "fileName": "费用报销制度",
        "chunks": [
            "差旅住宿费报销需要提供酒店发票、入住水单和出差审批单。缺少出差审批单的，财务可以退回报销申请。",
            "市内交通费单次超过二百元的，需要在报销单中说明事由，并上传行程凭证。普通通勤费用不属于报销范围。",
            "业务招待费报销需要注明客户名称、招待事由、参与人员和审批人。单次超过一千元的，必须提前获得部门负责人审批。",
        ],
    },
    {
        "fileName": "信息安全管理规范",
        "chunks": [
            "员工不得通过个人邮箱、网盘或即时通讯工具外发公司敏感资料。确需对外发送的，应使用公司批准的加密通道并保留审批记录。",
            "生产系统账号必须实名申请，禁止多人共用同一账号。账号权限应遵循最小权限原则，离职或岗位变更时应及时回收。",
            "涉及客户数据的导出操作需要经过数据负责人审批，并记录导出目的、字段范围、接收人和保留期限。",
        ],
    },
    {
        "fileName": "IT服务台使用说明",
        "chunks": [
            "员工遇到电脑、网络、打印机或办公软件故障时，应在IT服务台提交工单。工单必须填写故障现象、影响范围、联系方式和期望处理时间。",
            "紧急故障是指影响生产系统、客户交付或整个部门办公的故障。紧急故障可以电话联系IT值班人员，同时仍需在IT服务台补充工单记录。",
            "普通办公软件安装申请需要说明软件名称、用途、使用人和授权来源。未经授权的软件不得安装在公司电脑上。",
        ],
    },
    {
        "fileName": "账号与密码使用说明",
        "chunks": [
            "员工首次登录统一身份平台后，应立即修改初始密码。密码长度不得少于十二位，并应包含大写字母、小写字母、数字或特殊字符中的至少三类。",
            "员工遗忘统一身份平台密码时，应通过统一身份平台的找回密码功能重置。无法自助重置的，可以提交IT服务台工单并完成人员身份核验。",
            "管理员账号必须开启多因素认证。管理员不得将验证码、动态口令或恢复码转发给他人。",
        ],
    },
    {
        "fileName": "VPN远程办公使用说明",
        "chunks": [
            "员工远程访问公司内网时，应使用公司批准的VPN客户端。首次使用VPN需要提交远程办公权限申请，并经过直属主管审批。",
            "VPN连接失败时，应先检查网络、账号状态和多因素认证是否正常。连续三次认证失败后，账号可能被临时锁定，需要联系IT服务台处理。",
            "远程办公期间不得将VPN账号借给他人使用，也不得在公共电脑上保存公司账号、密码或业务资料。",
        ],
    },
    {
        "fileName": "采购申请操作手册",
        "chunks": [
            "采购申请应在采购系统中发起，申请人需要填写采购物品、数量、预算金额、用途说明和期望到货时间。",
            "单笔采购预算超过五千元的，需要部门负责人审批。单笔采购预算超过两万元的，还需要财务负责人复核。",
            "采购到货后，申请人应在三个工作日内完成验收确认。验收不通过的，应在采购系统中填写不通过原因并联系供应商处理。",
        ],
    },
    {
        "fileName": "合同审批使用说明",
        "chunks": [
            "合同审批应通过合同管理系统发起。申请人需要上传合同正文、合同附件、供应商信息和业务背景说明。",
            "涉及客户数据、源代码、商业秘密或排他性条款的合同，必须经过法务复核后才能提交盖章。",
            "合同盖章完成后，申请人应在五个工作日内上传扫描件并归档。纸质原件应按公司档案管理要求移交。",
        ],
    },
    {
        "fileName": "会议室预约使用说明",
        "chunks": [
            "员工预约会议室应通过办公门户提交申请，填写会议主题、参会人数、开始时间、结束时间和所需设备。",
            "超过二十人的会议应优先预约大会议室。需要视频会议设备的，应至少提前一个工作日确认设备可用性。",
            "会议取消或时间变更时，预约人应及时释放会议室资源，避免影响其他团队使用。",
        ],
    },
    {
        "fileName": "常见错误说法汇总-不得作为制度依据",
        "chunks": [
            "错误说法：年假可以当天口头申请，不需要主管审批。正确做法应以员工考勤与请假制度为准，本条只是错误示例，不得作为制度依据。",
            "错误说法：普通上下班通勤费用都可以按市内交通费报销。正确做法应以费用报销制度为准，本条只是错误示例，不得作为制度依据。",
            "错误说法：生产系统账号可以由项目组多人共用，只要组长知道密码即可。正确做法应以信息安全管理规范为准，本条只是错误示例，不得作为制度依据。",
            "错误说法：客户数据导出后可以永久保存，不需要记录接收人。正确做法应以信息安全管理规范为准，本条只是错误示例，不得作为制度依据。",
        ],
    },
    {
        "fileName": "已废止流程说明-不得作为当前依据",
        "chunks": [
            "已废止流程：员工可通过邮件直接向财务提交报销，不需要在系统中填写报销单。该流程已经废止，不得作为当前报销依据。",
            "已废止流程：采购预算低于三万元时不需要任何审批。该流程已经废止，不得作为当前采购依据。",
            "已废止流程：合同金额低于十万元时可以先盖章后补审批。该流程已经废止，不得作为当前合同审批依据。",
            "已废止流程：VPN远程办公权限默认向全员开放，不需要申请。该流程已经废止，不得作为当前远程办公依据。",
        ],
    },
    {
        "fileName": "错误操作案例-仅供培训反例",
        "chunks": [
            "错误操作案例：员工将管理员动态口令截图发送到群聊，导致账号存在被冒用风险。该案例用于说明禁止共享验证码和动态口令。",
            "错误操作案例：员工在公共电脑上保存VPN密码，离开后未退出登录。该案例用于说明远程办公不得在公共电脑保存公司账号和业务资料。",
            "错误操作案例：申请人采购到货后长期不验收，导致供应商结算延迟。该案例用于说明到货后三个工作日内完成验收确认。",
            "错误操作案例：会议取消后未释放会议室，导致其他团队无法预约。该案例用于说明会议变更时应及时释放会议室资源。",
        ],
    },
    {
        "fileName": "知识库提问使用说明",
        "chunks": [
            "向知识库提问时，建议说明业务场景、涉及系统和需要判断的问题。例如，可以询问年假申请提前多久、差旅住宿费需要哪些材料、VPN权限如何申请。",
            "当知识库回答没有找到明确依据时，用户应联系对应制度负责人或系统管理员确认，不应自行编造流程。",
            "知识库中的错误说法、已废止流程和培训反例仅用于识别风险，不应作为当前制度或操作依据。",
        ],
    },
]


EVAL_QUESTIONS = [
    {
        "id": "q001",
        "question": "年假需要提前多久申请？",
        "expectedAnswer": "员工申请年假需要至少提前三个工作日提交申请，并经过直属主管审批。",
        "expectedEvidence": ["年假需要至少提前三个工作日提交申请"],
        "answerable": True,
    },
    {
        "id": "q002",
        "question": "连续年假超过五个工作日还需要谁复核？",
        "expectedAnswer": "连续年假超过五个工作日的，还需要部门负责人复核。",
        "expectedEvidence": ["连续年假超过五个工作日", "部门负责人复核"],
        "answerable": True,
    },
    {
        "id": "q003",
        "question": "病假证明最晚什么时候提交？",
        "expectedAnswer": "员工申请病假需要在返岗后三个工作日内提交医院诊断证明或病历材料。",
        "expectedEvidence": ["返岗后三个工作日内提交医院诊断证明"],
        "answerable": True,
    },
    {
        "id": "q004",
        "question": "差旅住宿费报销需要哪些材料？",
        "expectedAnswer": "需要提供酒店发票、入住水单和出差审批单。",
        "expectedEvidence": ["酒店发票", "入住水单", "出差审批单"],
        "answerable": True,
    },
    {
        "id": "q005",
        "question": "市内交通费超过多少钱需要说明事由？",
        "expectedAnswer": "市内交通费单次超过二百元的，需要说明事由并上传行程凭证。",
        "expectedEvidence": ["单次超过二百元", "说明事由", "上传行程凭证"],
        "answerable": True,
    },
    {
        "id": "q006",
        "question": "普通上下班通勤能报销吗？",
        "expectedAnswer": "不能，普通通勤费用不属于报销范围。",
        "expectedEvidence": ["普通通勤费用不属于报销范围"],
        "answerable": True,
    },
    {
        "id": "q007",
        "question": "员工可以用个人网盘发送公司敏感资料吗？",
        "expectedAnswer": "不可以，员工不得通过个人邮箱、网盘或即时通讯工具外发公司敏感资料。",
        "expectedEvidence": ["不得通过个人邮箱、网盘或即时通讯工具外发公司敏感资料"],
        "answerable": True,
    },
    {
        "id": "q008",
        "question": "生产系统账号可以多人共用吗？",
        "expectedAnswer": "不可以，生产系统账号必须实名申请，禁止多人共用同一账号。",
        "expectedEvidence": ["生产系统账号必须实名申请", "禁止多人共用同一账号"],
        "answerable": True,
    },
    {
        "id": "q009",
        "question": "导出客户数据需要记录哪些信息？",
        "expectedAnswer": "需要记录导出目的、字段范围、接收人和保留期限。",
        "expectedEvidence": ["导出目的", "字段范围", "接收人", "保留期限"],
        "answerable": True,
    },
    {
        "id": "q010",
        "question": "公司是否报销员工宠物医疗费？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
    {
        "id": "q011",
        "question": "员工婚假有多少天？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
    {
        "id": "q012",
        "question": "客户数据可以永久保存吗？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
    {
        "id": "q013",
        "question": "电脑或网络故障应该在哪里提交工单？",
        "expectedAnswer": "应在IT服务台提交工单，并填写故障现象、影响范围、联系方式和期望处理时间。",
        "expectedEvidence": ["IT服务台提交工单", "故障现象", "影响范围", "联系方式", "期望处理时间"],
        "answerable": True,
    },
    {
        "id": "q014",
        "question": "紧急故障可以只打电话不提交工单吗？",
        "expectedAnswer": "不可以。紧急故障可以电话联系IT值班人员，但仍需在IT服务台补充工单记录。",
        "expectedEvidence": ["可以电话联系IT值班人员", "仍需在IT服务台补充工单记录"],
        "answerable": True,
    },
    {
        "id": "q015",
        "question": "统一身份平台初始密码有什么修改要求？",
        "expectedAnswer": "首次登录后应立即修改初始密码，密码不少于十二位，并包含大写字母、小写字母、数字或特殊字符中的至少三类。",
        "expectedEvidence": ["立即修改初始密码", "不得少于十二位", "至少三类"],
        "answerable": True,
    },
    {
        "id": "q016",
        "question": "管理员可以把动态口令发给同事吗？",
        "expectedAnswer": "不可以，管理员不得将验证码、动态口令或恢复码转发给他人。",
        "expectedEvidence": ["管理员不得将验证码、动态口令或恢复码转发给他人"],
        "answerable": True,
    },
    {
        "id": "q017",
        "question": "首次使用VPN需要经过谁审批？",
        "expectedAnswer": "首次使用VPN需要提交远程办公权限申请，并经过直属主管审批。",
        "expectedEvidence": ["提交远程办公权限申请", "直属主管审批"],
        "answerable": True,
    },
    {
        "id": "q018",
        "question": "VPN账号可以借给别人用吗？",
        "expectedAnswer": "不可以，远程办公期间不得将VPN账号借给他人使用。",
        "expectedEvidence": ["不得将VPN账号借给他人使用"],
        "answerable": True,
    },
    {
        "id": "q019",
        "question": "采购申请需要填写哪些信息？",
        "expectedAnswer": "需要填写采购物品、数量、预算金额、用途说明和期望到货时间。",
        "expectedEvidence": ["采购物品", "数量", "预算金额", "用途说明", "期望到货时间"],
        "answerable": True,
    },
    {
        "id": "q020",
        "question": "单笔采购超过两万元需要谁复核？",
        "expectedAnswer": "单笔采购预算超过两万元的，需要财务负责人复核。",
        "expectedEvidence": ["超过两万元", "财务负责人复核"],
        "answerable": True,
    },
    {
        "id": "q021",
        "question": "采购到货后多久完成验收确认？",
        "expectedAnswer": "采购到货后，申请人应在三个工作日内完成验收确认。",
        "expectedEvidence": ["到货后", "三个工作日内完成验收确认"],
        "answerable": True,
    },
    {
        "id": "q022",
        "question": "哪些合同必须经过法务复核？",
        "expectedAnswer": "涉及客户数据、源代码、商业秘密或排他性条款的合同，必须经过法务复核。",
        "expectedEvidence": ["客户数据", "源代码", "商业秘密", "排他性条款", "法务复核"],
        "answerable": True,
    },
    {
        "id": "q023",
        "question": "合同盖章后多久上传扫描件并归档？",
        "expectedAnswer": "合同盖章完成后，申请人应在五个工作日内上传扫描件并归档。",
        "expectedEvidence": ["盖章完成后", "五个工作日内上传扫描件并归档"],
        "answerable": True,
    },
    {
        "id": "q024",
        "question": "预约会议室需要填写哪些内容？",
        "expectedAnswer": "需要填写会议主题、参会人数、开始时间、结束时间和所需设备。",
        "expectedEvidence": ["会议主题", "参会人数", "开始时间", "结束时间", "所需设备"],
        "answerable": True,
    },
    {
        "id": "q025",
        "question": "需要视频会议设备时要提前多久确认？",
        "expectedAnswer": "需要视频会议设备的，应至少提前一个工作日确认设备可用性。",
        "expectedEvidence": ["至少提前一个工作日确认设备可用性"],
        "answerable": True,
    },
    {
        "id": "q026",
        "question": "知识库里的错误说法可以作为当前制度依据吗？",
        "expectedAnswer": "不可以，错误说法、已废止流程和培训反例仅用于识别风险，不应作为当前制度或操作依据。",
        "expectedEvidence": ["错误说法、已废止流程和培训反例", "不应作为当前制度或操作依据"],
        "answerable": True,
    },
    {
        "id": "q027",
        "question": "年假可以当天口头申请吗？",
        "expectedAnswer": "不可以，年假需要至少提前三个工作日提交申请，并经过直属主管审批。",
        "expectedEvidence": ["年假需要至少提前三个工作日提交申请", "直属主管审批"],
        "answerable": True,
    },
    {
        "id": "q028",
        "question": "普通通勤费用是不是都可以报销？",
        "expectedAnswer": "不是，普通通勤费用不属于报销范围。",
        "expectedEvidence": ["普通通勤费用不属于报销范围"],
        "answerable": True,
    },
    {
        "id": "q029",
        "question": "采购预算低于三万元是不是不需要审批？",
        "expectedAnswer": "不是。单笔采购预算超过五千元需要部门负责人审批，超过两万元还需要财务负责人复核。",
        "expectedEvidence": ["超过五千元", "部门负责人审批", "超过两万元", "财务负责人复核"],
        "answerable": True,
    },
    {
        "id": "q030",
        "question": "合同能不能先盖章后补审批？",
        "expectedAnswer": "不能，合同审批应通过合同管理系统发起，涉及特定风险条款的合同必须经过法务复核后才能提交盖章。",
        "expectedEvidence": ["合同审批应通过合同管理系统发起", "法务复核后才能提交盖章"],
        "answerable": True,
    },
    {
        "id": "q031",
        "question": "公司有没有午餐补贴标准？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
    {
        "id": "q032",
        "question": "办公电脑几年可以更换一次？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
    {
        "id": "q033",
        "question": "外地员工租房补贴是多少？",
        "expectedAnswer": "当前知识库中没有找到明确依据。",
        "expectedEvidence": [],
        "answerable": False,
    },
]


REFUSAL_MARKERS = [
    "没有找到明确依据",
    "未找到明确依据",
    "知识库中没有",
    "资料中没有",
    "无法根据资料",
    "没有相关信息",
]


@dataclass
class SearchEvaluation:
    hit_at_1: bool
    hit_at_3: bool
    hit_at_5: bool
    reciprocal_rank: float
    matched_rank: int | None
    matched_evidence: list[str]
    hits: list[dict[str, Any]]


@dataclass
class ChatEvaluation:
    correct: bool
    refusal_correct: bool | None
    answer_contains_evidence: bool
    matched_evidence: list[str]
    missing_evidence: list[str]
    answer: str
    sources: list[dict[str, Any]]


class RagEvalClient:
    def __init__(self, base_url: str, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds

    def post_json(self, path: str, payload: dict[str, Any]) -> Any:
        url = self.base_url + path
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                text = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            error_text = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"POST {url} failed: HTTP {exc.code}: {error_text}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"POST {url} failed: {exc}") from exc
        return json.loads(text) if text else None

    def post_sse(self, path: str, payload: dict[str, Any]) -> list[dict[str, str]]:
        url = self.base_url + path
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "text/event-stream",
            },
            method="POST",
        )
        events: list[dict[str, str]] = []
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                current_event = "message"
                data_lines: list[str] = []
                for raw_line in response:
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line == "":
                        if data_lines:
                            events.append({"event": current_event, "data": "\n".join(data_lines)})
                        current_event = "message"
                        data_lines = []
                        continue
                    if line.startswith(":"):
                        continue
                    if line.startswith("event:"):
                        current_event = line[len("event:") :].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[len("data:") :].lstrip())
                if data_lines:
                    events.append({"event": current_event, "data": "\n".join(data_lines)})
        except urllib.error.HTTPError as exc:
            error_text = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"POST {url} failed: HTTP {exc.code}: {error_text}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"POST {url} failed: {exc}") from exc
        return events


def upload_eval_documents(client: RagEvalClient, department_id: str, module_code: str) -> list[dict[str, Any]]:
    results = []
    for document in EVAL_DOCUMENTS:
        payload = {
            "fileName": document["fileName"],
            "departmentId": department_id,
            "moduleCode": module_code,
            "uploadedBy": "rag-eval",
            "chunks": document["chunks"],
        }
        results.append(client.post_json("/api/rag/documents/manual", payload))
    return results


def run_search(client: RagEvalClient, department_id: str, module_code: str, question: str, top_k: int) -> list[dict[str, Any]]:
    payload = {
        "departmentId": department_id,
        "moduleCode": module_code,
        "question": question,
        "topK": top_k,
    }
    result = client.post_json("/api/rag/search", payload)
    if not isinstance(result, list):
        raise RuntimeError(f"Unexpected search response: {result!r}")
    return result


def run_chat(client: RagEvalClient, department_id: str, module_code: str, question: str) -> tuple[list[dict[str, Any]], str]:
    payload = {
        "departmentId": department_id,
        "moduleCode": module_code,
        "question": question,
    }
    events = client.post_sse("/api/rag/chat/stream", payload)
    sources: list[dict[str, Any]] = []
    answer_parts: list[str] = []
    for event in events:
        event_name = event["event"]
        data = event["data"]
        if event_name == "sources":
            try:
                decoded = json.loads(data)
            except json.JSONDecodeError:
                decoded = []
            if isinstance(decoded, list):
                sources = decoded
        elif event_name == "message":
            answer_parts.append(data)
    return sources, "".join(answer_parts).strip()


def evaluate_search(question: dict[str, Any], hits: list[dict[str, Any]], top_k: int) -> SearchEvaluation:
    evidence = question["expectedEvidence"]
    if not question["answerable"]:
        return SearchEvaluation(
            hit_at_1=len(hits) == 0,
            hit_at_3=len(hits[:3]) == 0,
            hit_at_5=len(hits[:top_k]) == 0,
            reciprocal_rank=1.0 if len(hits) == 0 else 0.0,
            matched_rank=None,
            matched_evidence=[],
            hits=hits,
        )

    matched_rank = None
    matched_evidence: list[str] = []
    for index, hit in enumerate(hits[:top_k], start=1):
        content = str(hit.get("content") or "")
        evidence_in_hit = [item for item in evidence if item in content]
        if evidence_in_hit:
            matched_rank = index
            matched_evidence = evidence_in_hit
            break

    reciprocal_rank = 0.0 if matched_rank is None else 1.0 / matched_rank
    return SearchEvaluation(
        hit_at_1=matched_rank == 1,
        hit_at_3=matched_rank is not None and matched_rank <= 3,
        hit_at_5=matched_rank is not None and matched_rank <= top_k,
        reciprocal_rank=reciprocal_rank,
        matched_rank=matched_rank,
        matched_evidence=matched_evidence,
        hits=hits,
    )


def evaluate_chat(question: dict[str, Any], sources: list[dict[str, Any]], answer: str) -> ChatEvaluation:
    evidence = question["expectedEvidence"]
    if not question["answerable"]:
        refusal_correct = contains_any(answer, REFUSAL_MARKERS)
        return ChatEvaluation(
            correct=refusal_correct,
            refusal_correct=refusal_correct,
            answer_contains_evidence=False,
            matched_evidence=[],
            missing_evidence=[],
            answer=answer,
            sources=sources,
        )

    matched_evidence = [item for item in evidence if item in answer]
    missing_evidence = [item for item in evidence if item not in answer]
    source_text = "\n".join(str(source.get("content") or "") for source in sources)
    sources_support_answer = all(item in source_text for item in evidence)
    answer_contains_evidence = len(missing_evidence) == 0
    correct = answer_contains_evidence and sources_support_answer
    return ChatEvaluation(
        correct=correct,
        refusal_correct=None,
        answer_contains_evidence=answer_contains_evidence,
        matched_evidence=matched_evidence,
        missing_evidence=missing_evidence,
        answer=answer,
        sources=sources,
    )


def contains_any(text: str, markers: list[str]) -> bool:
    return any(marker in text for marker in markers)


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def build_summary(results: list[dict[str, Any]]) -> dict[str, Any]:
    search_results = [item["search"] for item in results if item.get("search") is not None]
    chat_results = [item["chat"] for item in results if item.get("chat") is not None]
    answerable_chat_results = [
        item["chat"] for item in results if item.get("chat") is not None and item["answerable"]
    ]
    unanswerable_chat_results = [
        item["chat"] for item in results if item.get("chat") is not None and not item["answerable"]
    ]

    return {
        "questionCount": len(results),
        "search": {
            "evaluatedCount": len(search_results),
            "hitAt1": mean([1.0 if item["hitAt1"] else 0.0 for item in search_results]),
            "hitAt3": mean([1.0 if item["hitAt3"] else 0.0 for item in search_results]),
            "hitAt5": mean([1.0 if item["hitAt5"] else 0.0 for item in search_results]),
            "mrr": mean([float(item["reciprocalRank"]) for item in search_results]),
        },
        "chat": {
            "evaluatedCount": len(chat_results),
            "accuracy": mean([1.0 if item["correct"] else 0.0 for item in chat_results]),
            "answerableAccuracy": mean([1.0 if item["correct"] else 0.0 for item in answerable_chat_results]),
            "refusalAccuracy": mean([1.0 if item["correct"] else 0.0 for item in unanswerable_chat_results]),
        },
    }


def print_detailed_report(report: dict[str, Any]) -> None:
    summary = report["summary"]
    print("\n========== RAG 评估结果汇总 ==========")
    print(f"后端地址：{report['baseUrl']}")
    print(f"评估范围：departmentId={report['departmentId']}，moduleCode={report['moduleCode']}")
    print(f"检索 TopK：{report['topK']}")
    print(f"问题总数：{summary['questionCount']}")

    print("\n---------- 检索指标 ----------")
    print(f"已评估问题数：{summary['search']['evaluatedCount']}")
    print(f"Hit@1：{as_percent(summary['search']['hitAt1'])}")
    print(f"Hit@3：{as_percent(summary['search']['hitAt3'])}")
    print(f"Hit@5：{as_percent(summary['search']['hitAt5'])}")
    print(f"MRR：{summary['search']['mrr']:.3f}")

    print("\n---------- 回答指标 ----------")
    print(f"已评估问题数：{summary['chat']['evaluatedCount']}")
    print(f"总体回答准确率：{as_percent(summary['chat']['accuracy'])}")
    print(f"可回答问题准确率：{as_percent(summary['chat']['answerableAccuracy'])}")
    print(f"无答案问题拒答准确率：{as_percent(summary['chat']['refusalAccuracy'])}")

    print("\n========== 每题详细结果 ==========")
    for item in report["results"]:
        print_question_detail(item)


def print_question_detail(item: dict[str, Any]) -> None:
    print("\n----------------------------------------")
    print(f"题号：{item['id']}")
    print(f"问题：{item['question']}")
    print(f"问题类型：{'可回答' if item['answerable'] else '无明确依据，应拒答'}")
    print(f"标准答案：{item['expectedAnswer']}")
    if item["expectedEvidence"]:
        print(f"期望证据：{join_values(item['expectedEvidence'])}")
    else:
        print("期望证据：无")

    search = item.get("search")
    if search is not None:
        print("\n  [检索结果]")
        print(f"  Hit@1：{yes_no(search['hitAt1'])}")
        print(f"  Hit@3：{yes_no(search['hitAt3'])}")
        print(f"  Hit@5：{yes_no(search['hitAt5'])}")
        print(f"  MRR贡献：{float(search['reciprocalRank']):.3f}")
        print(f"  首个命中排名：{search['matchedRank'] if search['matchedRank'] is not None else '未命中'}")
        print(f"  已匹配证据：{join_values(search['matchedEvidence']) if search['matchedEvidence'] else '无'}")
        print("  Top 命中片段：")
        for index, hit in enumerate(search.get("hits", [])[:5], start=1):
            file_name = hit.get("fileName") or "未知文件"
            chunk_index = hit.get("chunkIndex")
            score = hit.get("score")
            score_text = "无分数" if score is None else f"{float(score):.4f}"
            content = compact_text(str(hit.get("content") or ""), 140)
            print(f"    {index}. 文件：{file_name}，分片：{chunk_index}，分数：{score_text}")
            print(f"       内容：{content}")

    chat = item.get("chat")
    if chat is not None:
        print("\n  [回答结果]")
        print(f"  判断结果：{'正确' if chat['correct'] else '错误'}")
        if chat["refusalCorrect"] is not None:
            print(f"  拒答是否正确：{yes_no(chat['refusalCorrect'])}")
        else:
            print(f"  回答是否覆盖全部期望证据：{yes_no(chat['answerContainsEvidence'])}")
        print(f"  已匹配证据：{join_values(chat['matchedEvidence']) if chat['matchedEvidence'] else '无'}")
        print(f"  缺失证据：{join_values(chat['missingEvidence']) if chat['missingEvidence'] else '无'}")
        print(f"  模型回答：{compact_text(str(chat['answer'] or ''), 500)}")
        print("  回答来源：")
        for index, source in enumerate(chat.get("sources", [])[:6], start=1):
            file_name = source.get("fileName") or "未知文件"
            chunk_index = source.get("chunkIndex")
            score = source.get("score")
            score_text = "无分数" if score is None else f"{float(score):.4f}"
            content = compact_text(str(source.get("content") or ""), 120)
            print(f"    {index}. 文件：{file_name}，分片：{chunk_index}，分数：{score_text}")
            print(f"       内容：{content}")


def yes_no(value: bool) -> str:
    return "是" if value else "否"


def as_percent(value: float) -> str:
    return f"{value * 100:.2f}%"


def join_values(values: list[str]) -> str:
    return "；".join(values)


def compact_text(text: str, max_chars: int) -> str:
    normalized = " ".join(text.split())
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 3] + "..."


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate the local RAG system.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help=f"Backend base URL, default: {DEFAULT_BASE_URL}")
    parser.add_argument("--department-id", default=DEFAULT_DEPARTMENT_ID, help="Evaluation department id.")
    parser.add_argument("--module-code", default=DEFAULT_MODULE_CODE, help="Evaluation module code.")
    parser.add_argument("--top-k", type=int, default=DEFAULT_TOP_K, help=f"Search topK, default: {DEFAULT_TOP_K}")
    parser.add_argument("--timeout", type=int, default=120, help="HTTP timeout in seconds.")
    parser.add_argument("--upload", action="store_true", help="Upload built-in evaluation documents.")
    parser.add_argument("--run-search", action="store_true", help="Run retrieval evaluation.")
    parser.add_argument("--run-chat", action="store_true", help="Run final answer evaluation.")
    parser.add_argument("--report", default=str(DEFAULT_REPORT_PATH), help="JSON report output path.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.upload and not args.run_search and not args.run_chat:
        print("没有指定要执行的动作。请使用 --upload、--run-search 或 --run-chat。", file=sys.stderr)
        return 2

    client = RagEvalClient(args.base_url, args.timeout)
    report: dict[str, Any] = {
        "baseUrl": args.base_url,
        "departmentId": args.department_id,
        "moduleCode": args.module_code,
        "topK": args.top_k,
        "startedAtEpochSeconds": int(time.time()),
        "uploads": [],
        "results": [],
        "summary": {},
    }

    if args.upload:
        print("正在上传评估文档...")
        report["uploads"] = upload_eval_documents(client, args.department_id, args.module_code)
        print(f"已上传 {len(report['uploads'])} 个评估文档。")

    if args.run_search or args.run_chat:
        for question in EVAL_QUESTIONS:
            print(f"正在评估 {question['id']}：{question['question']}")
            item: dict[str, Any] = {
                "id": question["id"],
                "question": question["question"],
                "answerable": question["answerable"],
                "expectedAnswer": question["expectedAnswer"],
                "expectedEvidence": question["expectedEvidence"],
                "search": None,
                "chat": None,
            }

            if args.run_search:
                hits = run_search(client, args.department_id, args.module_code, question["question"], args.top_k)
                search_eval = evaluate_search(question, hits, args.top_k)
                item["search"] = {
                    "hitAt1": search_eval.hit_at_1,
                    "hitAt3": search_eval.hit_at_3,
                    "hitAt5": search_eval.hit_at_5,
                    "reciprocalRank": search_eval.reciprocal_rank,
                    "matchedRank": search_eval.matched_rank,
                    "matchedEvidence": search_eval.matched_evidence,
                    "hits": search_eval.hits,
                }

            if args.run_chat:
                sources, answer = run_chat(client, args.department_id, args.module_code, question["question"])
                chat_eval = evaluate_chat(question, sources, answer)
                item["chat"] = {
                    "correct": chat_eval.correct,
                    "refusalCorrect": chat_eval.refusal_correct,
                    "answerContainsEvidence": chat_eval.answer_contains_evidence,
                    "matchedEvidence": chat_eval.matched_evidence,
                    "missingEvidence": chat_eval.missing_evidence,
                    "answer": chat_eval.answer,
                    "sources": chat_eval.sources,
                }

            report["results"].append(item)

    report["summary"] = build_summary(report["results"])
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print_detailed_report(report)
    print(f"\n详细 JSON 报告已写入：{report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
