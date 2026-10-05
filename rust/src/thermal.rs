/// Move synthesis between big/little cores by measured speed (see apply_affinity).
const ADAPTIVE_AFFINITY: bool = false;

use std::time::{Duration, Instant};
#[allow(unused_imports)]
use anyhow::Result;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum SocClass {
    Flagship,    // SD 8 Gen 2/3, Dimensity 9200+
    HighEnd,     // SD 7+ Gen 2, Dimensity 8200
    MidRange,    // SD 695, Dimensity 700
    LowEnd,      // SD 680, entry-level
}

#[derive(Debug, Clone)]
pub struct CpuTopology {
    pub prime_core: Option<usize>,
    pub perf_cores: Vec<usize>,
    pub little_cores: Vec<usize>,
    pub soc_class: SocClass,
}

impl CpuTopology {
    pub fn detect() -> Self {
        let mut cores_with_freq: Vec<(usize, u64)> = Vec::new();
        
        for cpu in 0..16 {
            let freq_path = format!("/sys/devices/system/cpu/cpu{}/cpufreq/cpuinfo_max_freq", cpu);
            if let Ok(freq_str) = std::fs::read_to_string(&freq_path) {
                if let Ok(freq) = freq_str.trim().parse::<u64>() {
                    cores_with_freq.push((cpu, freq));
                }
            }
        }
        
        if cores_with_freq.is_empty() {
            return Self::fallback();
        }
        
        cores_with_freq.sort_by(|a, b| b.1.cmp(&a.1));
        
        let max_freq = cores_with_freq[0].1;
        let num_cores = cores_with_freq.len();
        
        // Classify SoC based on topology
        let soc_class = Self::classify_soc(max_freq, num_cores);
        
        log::info!("Detected SoC class: {:?}, max freq: {} MHz, cores: {}", 
            soc_class, max_freq / 1000, num_cores);
        
        // Parse topology based on frequency tiers
        let (prime_core, perf_cores, little_cores) = 
            Self::parse_topology(&cores_with_freq, soc_class);
        
        Self {
            prime_core,
            perf_cores,
            little_cores,
            soc_class,
        }
    }
    
    fn classify_soc(max_freq: u64, num_cores: usize) -> SocClass {
        // Flagship: Prime core > 3.0 GHz, 8+ cores
        if max_freq > 3_000_000 && num_cores >= 8 {
            SocClass::Flagship
        }
        // High-end: Max freq > 2.5 GHz, 8 cores
        else if max_freq > 2_500_000 && num_cores >= 8 {
            SocClass::HighEnd
        }
        // Mid-range: Max freq > 2.0 GHz
        else if max_freq > 2_000_000 {
            SocClass::MidRange
        }
        // Low-end: Everything else
        else {
            SocClass::LowEnd
        }
    }
    
    fn parse_topology(
        cores_with_freq: &[(usize, u64)],
        soc_class: SocClass,
    ) -> (Option<usize>, Vec<usize>, Vec<usize>) {
        let max_freq = cores_with_freq[0].1;
        let min_freq = cores_with_freq.last().unwrap().1;
        let freq_range = max_freq - min_freq;
        
        match soc_class {
            SocClass::Flagship => {
                // 1 Prime + 3-4 Perf + 3-4 Little
                let prime_threshold = max_freq - (freq_range / 20); // Top 5%
                let perf_threshold = max_freq - (freq_range / 3);   // Middle tier
                
                let mut prime = None;
                let mut perf = Vec::new();
                let mut little = Vec::new();
                
                for &(cpu, freq) in cores_with_freq {
                    if prime.is_none() && freq >= prime_threshold {
                        prime = Some(cpu);
                    } else if freq >= perf_threshold {
                        perf.push(cpu);
                    } else {
                        little.push(cpu);
                    }
                }
                
                (prime, perf, little)
            }
            
            SocClass::HighEnd | SocClass::MidRange => {
                // 2-4 Big + 4-6 Little (no prime)
                let perf_threshold = max_freq - (freq_range / 4);
                
                let mut perf = Vec::new();
                let mut little = Vec::new();
                
                for &(cpu, freq) in cores_with_freq {
                    if freq >= perf_threshold {
                        perf.push(cpu);
                    } else {
                        little.push(cpu);
                    }
                }
                
                (None, perf, little)
            }
            
            SocClass::LowEnd => {
                // Often homogeneous or 4+4
                if freq_range < 200_000 {
                    // Homogeneous - treat all as "perf"
                    let all_cores: Vec<_> = cores_with_freq.iter().map(|&(cpu, _)| cpu).collect();
                    (None, all_cores, vec![])
                } else {
                    let mid = cores_with_freq.len() / 2;
                    let perf: Vec<_> = cores_with_freq[..mid].iter().map(|&(cpu, _)| cpu).collect();
                    let little: Vec<_> = cores_with_freq[mid..].iter().map(|&(cpu, _)| cpu).collect();
                    (None, perf, little)
                }
            }
        }
    }
    
    fn fallback() -> Self {
        log::warn!("Could not detect CPU topology, using fallback");
        Self {
            prime_core: None,
            perf_cores: (0..4).collect(),
            little_cores: (4..8).collect(),
            soc_class: SocClass::MidRange,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ThermalMode {
    ColdStart,
    Sustained,
    Efficiency,
    Emergency,
}

/// SoC-specific thermal configuration
#[derive(Debug, Clone)]
pub struct ThermalConfig {
    // Buffer thresholds
    pub buffer_low_threshold: f32,
    pub buffer_high_threshold: f32,
    
    // Thermal budgets
    pub max_high_power_duration: Duration,
    pub cooldown_duration: Duration,

    // Core selection strategy
    #[allow(dead_code)]
    pub use_prime_core: bool,
    pub sustained_mode_cores: CoreSelection,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CoreSelection {
    AllPerf,           // Use all performance cores
    SinglePerf,        // Use 1 performance core
    HybridPerfLittle,  // Mix of perf + little
    #[allow(dead_code)]
    AllLittle,         // Use all little cores
    #[allow(dead_code)]
    MinimalLittle,     // Use minimal little cores
}

impl ThermalConfig {
    pub fn for_soc_class(soc_class: SocClass) -> Self {
        match soc_class {
            SocClass::Flagship => Self {
                // Flagship can sustain high power longer
                buffer_low_threshold: 3.0,      // Less aggressive buffering
                buffer_high_threshold: 12.0,
                max_high_power_duration: Duration::from_secs(180), // 3 minutes
                cooldown_duration: Duration::from_secs(45),
                use_prime_core: false,          // Skip prime (too hot)
                sustained_mode_cores: CoreSelection::AllPerf,
            },
            
            SocClass::HighEnd => Self {
                buffer_low_threshold: 2.5,
                buffer_high_threshold: 10.0,
                max_high_power_duration: Duration::from_secs(120), // 2 minutes
                cooldown_duration: Duration::from_secs(60),
                use_prime_core: false,
                sustained_mode_cores: CoreSelection::AllPerf,
            },
            
            SocClass::MidRange => Self {
                // Need aggressive thermal management (your SD 695 case)
                buffer_low_threshold: 2.0,
                buffer_high_threshold: 10.0,
                max_high_power_duration: Duration::from_secs(90), // 1.5 minutes
                cooldown_duration: Duration::from_secs(90),
                use_prime_core: false,
                sustained_mode_cores: CoreSelection::HybridPerfLittle,
            },
            
            SocClass::LowEnd => Self {
                // Very conservative
                buffer_low_threshold: 1.5,
                buffer_high_threshold: 8.0,
                max_high_power_duration: Duration::from_secs(60), // 1 minute
                cooldown_duration: Duration::from_secs(120),
                use_prime_core: false,
                sustained_mode_cores: CoreSelection::SinglePerf,
            },
        }
    }
}

pub struct UnifiedThermalManager {
    topology: CpuTopology,
    config: ThermalConfig,
    current_mode: ThermalMode,
    
    // Thermal tracking
    high_power_usage_time: Duration,
    last_mode_switch: Instant,
    
    // Performance tracking
    baseline_rtf: Option<f32>,
    recent_rtf: Vec<f32>,
}

impl UnifiedThermalManager {
    pub fn new() -> Self {
        let topology = CpuTopology::detect();
        let config = ThermalConfig::for_soc_class(topology.soc_class);
        
        log::info!("Unified thermal manager initialized:");
        log::info!("  SoC: {:?}", topology.soc_class);
        log::info!("  Prime: {:?}", topology.prime_core);
        log::info!("  Perf: {:?}", topology.perf_cores);
        log::info!("  Little: {:?}", topology.little_cores);
        log::info!("  Config: buffer thresholds {:.1}s-{:.1}s, max power duration {:?}",
            config.buffer_low_threshold,
            config.buffer_high_threshold,
            config.max_high_power_duration
        );
        
        Self {
            topology,
            config,
            current_mode: ThermalMode::ColdStart,
            high_power_usage_time: Duration::ZERO,
            last_mode_switch: Instant::now(),
            baseline_rtf: None,
            recent_rtf: Vec::with_capacity(5),
        }
    }
    
    pub fn update(&mut self, buffer_seconds: f32, current_rtf: f32) -> ThermalMode {
        // Track RTF for throttling detection
        self.recent_rtf.push(current_rtf);
        if self.recent_rtf.len() > 5 {
            self.recent_rtf.remove(0);
        }
        
        if self.baseline_rtf.is_none() && self.recent_rtf.len() == 5 {
            self.baseline_rtf = Some(self.recent_rtf.iter().sum::<f32>() / 5.0);
            log::info!("Baseline RTF established: {:.2}x", self.baseline_rtf.unwrap());
        }
        
        let rtf_degradation = self.detect_throttling();
        let elapsed = self.last_mode_switch.elapsed();
        
        // Track high-power usage
        if matches!(self.current_mode, ThermalMode::ColdStart | ThermalMode::Sustained) {
            self.high_power_usage_time += elapsed;
        }
        
        let new_mode = self.decide_mode(buffer_seconds, rtf_degradation, elapsed);
        
        if new_mode != self.current_mode {
            log::info!(
                "Mode transition: {:?} -> {:?} (buffer: {:.1}s, RTF: {:.2}x, thermal budget: {:?})",
                self.current_mode,
                new_mode,
                buffer_seconds,
                current_rtf,
                self.high_power_usage_time
            );
            
            self.current_mode = new_mode;
            self.last_mode_switch = Instant::now();
            
            // Reset thermal budget when entering efficiency mode
            if matches!(new_mode, ThermalMode::Efficiency) {
                self.high_power_usage_time = Duration::ZERO;
            }
            
            self.apply_affinity().ok();
        }
        
        new_mode
    }
    
    fn decide_mode(&self, buffer_seconds: f32, rtf_degradation: bool, elapsed: Duration) -> ThermalMode {
        match self.current_mode {
            ThermalMode::ColdStart => {
                // Transition based on buffer fill or time limit
                if buffer_seconds > 5.0 || elapsed > Duration::from_secs(30) {
                    ThermalMode::Sustained
                } else {
                    ThermalMode::ColdStart
                }
            }
            
            ThermalMode::Sustained => {
                // Buffer critical - boost back to cold start
                if buffer_seconds < self.config.buffer_low_threshold {
                    ThermalMode::ColdStart
                }
                // Thermal budget exhausted or throttling detected
                else if self.high_power_usage_time > self.config.max_high_power_duration 
                    || rtf_degradation {
                    ThermalMode::Efficiency
                }
                // Buffer healthy - can afford to cool down
                else if buffer_seconds > self.config.buffer_high_threshold {
                    ThermalMode::Efficiency
                } else {
                    ThermalMode::Sustained
                }
            }
            
            ThermalMode::Efficiency => {
                // Buffer running low
                if buffer_seconds < self.config.buffer_low_threshold {
                    ThermalMode::Sustained
                }
                // Cooled down sufficiently
                else if elapsed > self.config.cooldown_duration 
                    && buffer_seconds < self.config.buffer_high_threshold {
                    ThermalMode::Sustained
                }
                // Severe throttling even on little cores
                else if rtf_degradation {
                    ThermalMode::Emergency
                } else {
                    ThermalMode::Efficiency
                }
            }
            
            ThermalMode::Emergency => {
                // Need significant cooling before returning
                if elapsed > Duration::from_secs(120) && !rtf_degradation {
                    ThermalMode::Efficiency
                } else {
                    ThermalMode::Emergency
                }
            }
        }
    }
    
    fn apply_affinity(&self) -> anyhow::Result<()> {
        // Disabled: a slowdown is not necessarily heat. With the screen off the
        // phone itself cuts background CPU; moving synthesis to little cores on
        // top of that ("Efficiency" → "Emergency", ≥120 s) turned it into long
        // gaps between phrases. The OS already manages thermals.
        if !ADAPTIVE_AFFINITY {
            return Ok(());
        }
        let cores = self.get_cores_for_mode();
        set_cpu_affinity(&cores)?;
        
        log::info!("Applied {:?} mode on {:?} SoC: {} threads on cores {:?}",
            self.current_mode,
            self.topology.soc_class,
            cores.len(),
            cores
        );
        
        Ok(())
    }
    
    fn get_cores_for_mode(&self) -> Vec<usize> {
        match self.current_mode {
            ThermalMode::ColdStart => {
                // Max performance
                match self.topology.soc_class {
                    SocClass::Flagship | SocClass::HighEnd => {
                        // Use all perf cores (skip prime)
                        self.topology.perf_cores.clone()
                    }
                    SocClass::MidRange | SocClass::LowEnd => {
                        // Use all perf cores
                        self.topology.perf_cores.clone()
                    }
                }
            }
            
            ThermalMode::Sustained => {
                match self.config.sustained_mode_cores {
                    CoreSelection::AllPerf => {
                        self.topology.perf_cores.clone()
                    }
                    CoreSelection::SinglePerf => {
                        vec![self.topology.perf_cores[0]]
                    }
                    CoreSelection::HybridPerfLittle => {
                        // 1 big + 2 little (spreads heat)
                        let mut cores = vec![self.topology.perf_cores[0]];
                        let little_count = 2.min(self.topology.little_cores.len());
                        cores.extend(&self.topology.little_cores[..little_count]);
                        cores
                    }
                    _ => self.topology.perf_cores.clone(),
                }
            }
            
            ThermalMode::Efficiency => {
                // Use little cores based on SoC class
                let count = match self.topology.soc_class {
                    SocClass::Flagship => 4,
                    SocClass::HighEnd => 4,
                    SocClass::MidRange => 4,
                    SocClass::LowEnd => 2,
                };
                
                let available = count.min(self.topology.little_cores.len());
                self.topology.little_cores[..available].to_vec()
            }
            
            ThermalMode::Emergency => {
                // Minimal cores
                let count = 2.min(self.topology.little_cores.len());
                self.topology.little_cores[..count].to_vec()
            }
        }
    }

    #[allow(dead_code)]
    pub fn get_thread_count(&self) -> usize {
        self.get_cores_for_mode().len().max(1)
    }

    fn detect_throttling(&self) -> bool {
        if let Some(baseline) = self.baseline_rtf {
            if self.recent_rtf.len() >= 3 {
                let current_avg = self.recent_rtf.iter().sum::<f32>() / self.recent_rtf.len() as f32;
                // If RTF dropped below 60% of baseline, we're throttling
                return current_avg < baseline * 0.6;
            }
        }
        false
    }
    
    pub fn get_soc_class(&self) -> SocClass {
        self.topology.soc_class
    }

    #[allow(dead_code)]
    pub fn get_current_mode(&self) -> ThermalMode {
        self.current_mode
    }
}

#[cfg(target_os = "android")]
fn set_cpu_affinity(cores: &[usize]) -> anyhow::Result<()> {
    use libc::{cpu_set_t, sched_setaffinity, CPU_SET, CPU_ZERO};
    
    unsafe {
        let mut cpuset: cpu_set_t = std::mem::zeroed();
        CPU_ZERO(&mut cpuset);
        
        for &cpu in cores {
            CPU_SET(cpu, &mut cpuset);
        }
        
        if sched_setaffinity(0, std::mem::size_of::<cpu_set_t>(), &cpuset) != 0 {
            return Err(anyhow::anyhow!("Failed to set CPU affinity"));
        }
    }
    
    Ok(())
}

#[cfg(not(target_os = "android"))]
fn set_cpu_affinity(_cores: &[usize]) -> anyhow::Result<()> {
    Ok(())
}
