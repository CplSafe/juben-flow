import copy
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('staged_model',Path(__file__).with_name('update-staged-model.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class StagedModelTest(unittest.TestCase):
 def test_handoffs_preserve_or_sign_and_later_steps(self):
  tail={'id':'DirectorReview','type':11,'candidateStrategy':30,'candidateParam':'142','childNode':{'id':'EndEvent','type':1}}
  final={'id':'ScriptReview','type':11,'approveMethod':3,'candidateStrategy':30,'candidateParam':'143,144','childNode':tail}
  first=copy.deepcopy(final);first.update(id='OutlineReview',childNode=final)
  creative=copy.deepcopy(final);creative.update(id='CreativeReview',childNode=first)
  original=copy.deepcopy(creative);new,changed=module.transform(creative)
  self.assertTrue(changed);self.assertEqual(creative,original)
  by_id={n['id']:n for n in module.nodes(new)}
  self.assertEqual([n['id'] for n in module.nodes(new)],['CreativeReview','StudentDraft','OutlineReview','StudentRevision','ScriptReview','DirectorReview','EndEvent'])
  for key in ['StudentDraft','StudentRevision']:
   self.assertEqual(by_id[key]['type'],13);self.assertEqual(by_id[key]['candidateStrategy'],36);self.assertEqual(by_id[key]['assignStartUserHandlerType'],1)
  for key in ['CreativeReview','OutlineReview','ScriptReview']:self.assertEqual(by_id[key]['approveMethod'],3)
  self.assertEqual(by_id['DirectorReview']['candidateParam'],'142')
  self.assertFalse(module.transform(new)[1])
if __name__=='__main__':unittest.main()
