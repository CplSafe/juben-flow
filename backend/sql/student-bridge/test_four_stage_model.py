import copy, importlib.util, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('four',Path(__file__).with_name('update-four-stage-model.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class FourStageTest(unittest.TestCase):
 def test_transform_preserves_old_chain_and_staff(self):
  nodes=[]
  for key in ['StartUserNode','CreativeReview','StudentDraft','OutlineReview','StudentRevision','ScriptReview','DirectorReview','EndEvent']:
   nodes.append({'id':key,'type':13 if key.startswith('Student') else 11,'candidateStrategy':36 if key.startswith('Student') else 30,'candidateParam':'143,144','approveMethod':3,'fieldsPermission':[{'field':'creative','permission':'read'},{'field':'script','permission':'write'}]})
  for a,b in zip(nodes,nodes[1:]):a['childNode']=b
  original=copy.deepcopy(nodes[0]);new,changed=m.transform(nodes[0]);self.assertTrue(changed);self.assertEqual(original,nodes[0])
  by={n['id']:n for n in m.nodes(new)}
  self.assertEqual([n['id'] for n in m.nodes(new)],['StartUserNode','CreativeReview','StudentDraft','OutlineReview','StudentScript','ScriptReview','StudentRevision','FinalReview','DirectorReview','EndEvent'])
  self.assertEqual(by['StudentScript']['candidateStrategy'],36)
  for review,target in [('CreativeReview','StartUserNode'),('OutlineReview','StudentDraft'),('ScriptReview','StudentScript'),('FinalReview','StudentRevision')]:
   self.assertEqual(by[review]['approveMethod'],3);self.assertEqual(by[review]['rejectHandler'],{'type':2,'returnNodeId':target})
  self.assertEqual(by['DirectorReview'],original['childNode']['childNode']['childNode']['childNode']['childNode']['childNode'])
  self.assertFalse(m.transform(new)[1])
if __name__=='__main__':unittest.main()
