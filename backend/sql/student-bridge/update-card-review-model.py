#!/usr/bin/env python3
"""Transform the current official SIMPLE model into graded card/refinement approval.
Existing instances retain their definition. Run through the shared official API publisher.
"""
import copy
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location("four",Path(__file__).with_name("update-four-stage-model.py"))
four=importlib.util.module_from_spec(spec);spec.loader.exec_module(four)

def transform(original):
    root=copy.deepcopy(original);chain=four.nodes(root);by={n["id"]:n for n in chain}
    if "GradedCardReview" in by:
        assert [n["id"] for n in chain]==["StartUserNode","CreativeReview","StudentDraft","GradedCardReview","StudentScript","CardRefinementReview","EndEvent"], "Unexpected graded chain"
        renamed=by["StudentScript"]["name"]!="学生完善剧本"
        by["StudentScript"]["name"]="学生完善剧本"
        return root,renamed
    assert all(k in by for k in ["CreativeReview","StudentDraft","OutlineReview","StudentScript","ScriptReview","StudentRevision","FinalReview","EndEvent"]), "Expected current four-stage model"
    assert all(by[key].get("approveMethod")==3 for key in ["CreativeReview","OutlineReview","ScriptReview"]), "Expected Sam/Yuge OR-sign reviews"
    by["OutlineReview"]["id"]="GradedCardReview";by["OutlineReview"]["name"]="一卡审核及A/S评级：Sam / 宇哥"
    by["StudentScript"]["name"]="学生完善剧本"
    by["ScriptReview"]["id"]="CardRefinementReview";by["ScriptReview"]["name"]="完善剧本审核：Sam / 宇哥"
    by["ScriptReview"]["childNode"]=by["EndEvent"]
    by["OutlineReview"]["rejectHandler"]={"type":2,"returnNodeId":"StudentDraft"}
    by["ScriptReview"]["rejectHandler"]={"type":2,"returnNodeId":"StudentScript"}
    return root,True

if __name__=="__main__":
    four.transform=transform
    four.main()
