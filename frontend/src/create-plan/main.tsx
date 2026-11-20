import React from 'react';
import '../shared/styles';
import './create-plan.css';
import { mountBundle } from '../shared/PdcaRoot';
import { CreatePlan } from './CreatePlan';

// The task form takes the surface of GZAC's task modal.
mountBundle(<CreatePlan />, 'cp-theme');
