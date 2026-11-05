import copy,importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('card',Path(__file__).with_name('update-card-review-model.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class CardReviewModelTest(unittest.TestCase):
 def test_new_definition_ends_after_refinement_without_touching_original(self):
  chain=[{'id':key,'approveMethod':3,'candidateParam':'143,144','candidateStrategy':30} for key in ['StartUserNode','CreativeReview','StudentDraft','OutlineReview','StudentScript','ScriptReview','StudentRevision','FinalReview','DirectorReview','Storyboard','Production','Release','EndEvent']]
  for a,b in zip(chain,chain[1:]):a['childNode']=b
  original=copy.deepcopy(chain[0]);updated,changed=m.transform(chain[0]);self.assertTrue(changed);self.assertEqual(original,chain[0])
  nodes=m.four.nodes(updated);self.assertEqual([n['id'] for n in nodes],['StartUserNode','CreativeReview','StudentDraft','GradedCardReview','StudentScript','CardRefinementReview','EndEvent'])
  for node in nodes:
   if node['id'] in ('GradedCardReview','CardRefinementReview'):self.assertEqual(node['approveMethod'],3);self.assertEqual(node['candidateParam'],'143,144')
  script=next(n for n in nodes if n["id"]=="StudentScript");self.assertEqual(script["name"],"学生完善剧本")
  old=copy.deepcopy(updated);next(n for n in m.four.nodes(old) if n["id"]=="StudentScript")["name"]="学生完善一卡剧本"
  renamed,changed=m.transform(old);self.assertTrue(changed);self.assertEqual(next(n for n in m.four.nodes(renamed) if n["id"]=="StudentScript")["name"],"学生完善剧本");self.assertFalse(m.transform(renamed)[1])
  self.assertFalse(m.transform(updated)[1]);self.assertEqual(nodes[-2]['rejectHandler']['returnNodeId'],'StudentScript')
if __name__=='__main__':unittest.main()
